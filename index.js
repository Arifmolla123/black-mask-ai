const express = require('express');
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const pino = require('pino');
const http = require('http');
const { Server } = require('socket.io');
const qrcode = require('qrcode');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server);

app.use(express.urlencoded({ extended: true }));
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

let sock;
let connectionStatus = 'Starting connection...';

async function startWhatsApp(socket, phoneNumber = null) {
    const { state, saveCreds } = await useMultiFileAuthState('auth_session');

    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state,
        printQRInTerminal: false,
        browser: Browsers.macOS('Chrome') // ব্রাউজার প্রোফাইল সেট করা
    });

    // যদি ইউজার ফোন নম্বর দিয়ে পেয়ার করতে চায়
    if (phoneNumber && !sock.authState.creds.registered) {
        setTimeout(async () => {
            try {
                let code = await sock.requestPairingCode(phoneNumber);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                connectionStatus = 'Pairing code generated! Enter it in WhatsApp.';
                socket.emit('status', connectionStatus);
            } catch (err) {
                console.error('Pairing Code Error:', err);
                socket.emit('status', 'Failed to generate pairing code.');
            }
        }, 3000);
    }

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr && !phoneNumber) {
            try {
                const qrImage = await qrcode.toDataURL(qr);
                connectionStatus = 'Scan QR Code using WhatsApp';
                socket.emit('qr', qrImage);
                socket.emit('status', connectionStatus);
            } catch (err) {
                console.error('QR Generation Error:', err);
            }
        }

        if (connection === 'open') {
            connectionStatus = 'Connected Successfully!';
            socket.emit('qr', '');
            socket.emit('pairingCode', 'CONNECTED');
            socket.emit('status', connectionStatus);
            console.log('WhatsApp connected successfully!');
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            connectionStatus = 'Connection closed, reconnecting...';
            socket.emit('status', connectionStatus);
            
            if (shouldReconnect) {
                setTimeout(() => startWhatsApp(socket), 3000);
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', connectionStatus);

    startWhatsApp(socket);

    // ইউজার যখন ওয়েব পেজ থেকে ফোন নম্বর পাঠাবে
    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/[^0-9]/g, '');
        if (cleanNumber.length >= 10) {
            startWhatsApp(socket, cleanNumber);
        } else {
            socket.emit('status', 'Invalid phone number format!');
        }
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server is running on port ${PORT}`);
});
    
