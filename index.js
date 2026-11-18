const express = require('express');
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const pino = require('pino');
const http = require('http');
const { Server } = require('socket.io');
const path = require('path');
const fs = require('fs');

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

    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state,
        printQRInTerminal: false,
        browser: Browsers.macOS('Chrome'),
        syncFullHistory: false
    });

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect } = update;

        if (connection === 'open') {
            socket.emit('status', 'Connected Successfully!');
            socket.emit('pairingCode', 'CONNECTED');
            console.log('WhatsApp connected successfully via Pairing Code!');
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            if (shouldReconnect) {
                socket.emit('status', 'Reconnecting...');
            } else {
                socket.emit('status', 'Connection logged out.');
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);

    // কোড রিকোয়েস্ট করার সময় একটু দ্রুত এবং প্রিসাইজ করা
    setTimeout(async () => {
        try {
            if (!sock.authState.creds.registered) {
                socket.emit('status', 'Requesting pairing code...');
                let code = await sock.requestPairingCode(phoneNumber);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                socket.emit('status', 'Enter this code in WhatsApp now!');
            }
        } catch (err) {
            console.error('Pairing Code Error:', err);
            socket.emit('status', 'Error! Please refresh page and try again.');
        }
    }, 4000);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'System ready. Enter your phone number.');

    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/[^0-9]/g, '');
        if (cleanNumber.length >= 10) {
            socket.emit('status', 'Generating fresh pairing code...');
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
        
