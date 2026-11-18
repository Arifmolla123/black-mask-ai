const express = require('express');
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const pino = require('pino');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');
const { HttpsProxyAgent } = require('https-proxy-agent');

const app = express();
const server = http.createServer(app);
const io = new Server(server);

app.use(express.urlencoded({ extended: true }));
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

let sock;

async function startPairing(socket, phoneNumber) {
    const sessionDir = path.join(__dirname, 'auth_session');
    if (fs.existsSync(sessionDir)) {
        fs.rmSync(sessionDir, { recursive: true, force: true });
    }

    const { state, saveCreds } = await useMultiFileAuthState('auth_session');

    // একদম নির্ভুল প্রক্সি ইউআরএল ফরম্যাট
    const proxyUrl = 'http://nrta23q1-cc-IN:mo1KpFnV@gw.proxi.st:9999';
    const agent = new HttpsProxyAgent(proxyUrl);

    try {
        sock = makeWASocket({
            logger: pino({ level: 'silent' }),
            auth: state,
            printQRInTerminal: false,
            browser: Browsers.ubuntu('Chrome'),
            agent: agent
        });
    } catch (proxyErr) {
        console.error('Proxy Agent Initialization Error:', proxyErr);
        socket.emit('status', 'Proxy connection failed. Check credentials.');
        return;
    }

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect } = update;

        if (connection === 'open') {
            socket.emit('status', 'Connected Successfully via Proxy!');
            socket.emit('pairingCode', 'CONNECTED');
            console.log('WhatsApp connected successfully via Residential Proxy!');
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            if (shouldReconnect) {
                socket.emit('status', 'Reconnecting through proxy...');
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);

    setTimeout(async () => {
        try {
            if (!sock.authState.creds.registered) {
                socket.emit('status', 'Requesting pairing code via Indian Proxy...');
                const formattedPhone = phoneNumber.replace(/\D/g, '');
                let code = await sock.requestPairingCode(formattedPhone);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                socket.emit('status', 'Enter this code in your WhatsApp app!');
            }
        } catch (err) {
            console.error('Pairing Code Execution Error:', err);
            socket.emit('status', 'Proxy timeout or auth error! Refresh and retry.');
        }
    }, 7000);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'System ready with Proxy. Enter phone number.');

    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/\D/g, '');
        if (cleanNumber.length >= 10) {
            socket.emit('status', 'Routing through Residential Proxy...');
            if (sock) {
                try { sock.end(undefined); } catch (e) {}
            }
            startPairing(socket, cleanNumber);
        } else {
            socket.emit('status', 'Invalid phone number format!');
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server is running on port ${PORT}`);
});
    
