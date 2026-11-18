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
        browser: ['Chrome (Linux)', 'Chrome', '120.0.0.0'], // রিয়েলিস্টিক ব্রাউজার হেডার
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

    // সকেট স্টেবল হওয়ার জন্য ৬ সেকেন্ড অপেক্ষা করে পেয়ারিং কোড রিকোয়েস্ট পাঠানো
    setTimeout(async () => {
        try {
            if (!sock.authState.creds.registered) {
                socket.emit('status', 'Requesting pairing code from WhatsApp...');
                // হোয়াটসঅ্যাপের নিয়ম অনুযায়ী নম্বর ফরম্যাট ক্লিয়ার করা (+ বা স্পেস রিমুভ)
                const formattedPhone = phoneNumber.replace(/\D/g, '');
                let code = await sock.requestPairingCode(formattedPhone);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                socket.emit('status', 'Enter this code in your WhatsApp Linked Devices!');
            }
        } catch (err) {
            console.error('Pairing Code Error:', err);
            socket.emit('status', 'Failed to generate code. Please refresh and try again.');
        }
    }, 6000);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'System ready. Enter your phone number with country code.');

    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/\D/g, '');
        if (cleanNumber.length >= 10) {
            socket.emit('status', 'Cleaning session & requesting code...');
            if (sock) {
                try { sock.end(undefined); } catch (e) {}
            }
            startPairing(socket, cleanNumber);
        } else {
            socket.emit('status', 'Invalid phone number format! Include country code.');
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server is running on port ${PORT}`);
});
    
