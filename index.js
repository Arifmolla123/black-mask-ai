const express = require('express');
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const pino = require('pino');
const http = require('http');
const { Server } = require('socket.io');
const qrcode = require('qrcode');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server);

app.use(express.static(path.join(__dirname, 'public')));

let sock;
let connectionStatus = 'Starting connection...';

async function startWhatsApp(socket) {
    const { state, saveCreds } = await useMultiFileAuthState('auth_session');

    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state,
        printQRInTerminal: false
    });

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr) {
            try {
                // সরাসরি Baileys এর QR স্ট্রিং কে নিখুঁত ইমেজ ডেটা ইউআরএলে রূপান্তর করা
                const qrImage = await qrcode.toDataURL(qr);
                connectionStatus = 'Please scan the QR code using WhatsApp';
                socket.emit('qr', qrImage);
                socket.emit('status', connectionStatus);
            } catch (err) {
                console.error('QR Generation Error:', err);
            }
        }

        if (connection === 'open') {
            connectionStatus = 'Connected Successfully!';
            socket.emit('qr', '');
            socket.emit('status', connectionStatus);
            console.log('WhatsApp connected successfully!');
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            connectionStatus = 'Connection closed, reconnecting...';
            socket.emit('status', connectionStatus);
            
            if (shouldReconnect) {
                startWhatsApp(socket);
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', connectionStatus);

    if (connectionStatus.includes('Connected')) {
        socket.emit('status', 'Bot is already connected!');
    } else {
        startWhatsApp(socket);
    }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Server is running on port ${PORT}`);
});
