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

    // হোয়াটসঅ্যাপের সিকিউরিটি বাইপাস করার জন্য সঠিক ব্রাউজার হেডার
    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state,
        printQRInTerminal: false,
        browser: Browsers.ubuntu('Chrome')
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
                socket.emit('status', 'Connection lost, please refresh.');
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);

    // সকেট ওপেন হওয়ার জন্য পর্যাপ্ত সময় দিয়ে পেয়ারিং কোড চাওয়া
    setTimeout(async () => {
        try {
            if (!sock.authState.creds.registered) {
                socket.emit('status', 'Requesting pairing code from WhatsApp...');
                const formattedPhone = phoneNumber.replace(/\D/g, '');
                let code = await sock.requestPairingCode(formattedPhone);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                socket.emit('status', 'Enter this code in your WhatsApp app!');
            }
        } catch (err) {
            console.error('Pairing Code Error:', err);
            socket.emit('status', 'Failed to generate. Please refresh page and retry.');
        }
    }, 8000);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'Ready. Enter your phone number with country code.');

    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/\D/g, '');
        if (cleanNumber.length >= 10) {
            socket.emit('status', 'Initializing secure pairing...');
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
        
