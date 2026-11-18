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

async function startWhatsApp(socket, phoneNumber = null) {
    const { state, saveCreds } = await useMultiFileAuthState('auth_session');

    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state,
        printQRInTerminal: false,
        browser: Browsers.macOS('Chrome')
    });

    // যদি পেয়ারিং কোডের জন্য নম্বর দেওয়া হয় এবং রেজিস্ট্রেশন করা না থাকে
    if (phoneNumber && !sock.authState.creds.registered) {
        setTimeout(async () => {
            try {
                socket.emit('status', 'Requesting pairing code from WhatsApp...');
                // নম্বর ফরম্যাট ঠিক রাখা (কান্ট্রি কোড সহ)
                let code = await sock.requestPairingCode(phoneNumber);
                code = code?.match(/.{1,4}/g)?.join('-') || code;
                socket.emit('pairingCode', code);
                socket.emit('status', 'Enter this code in your WhatsApp Linked Devices!');
            } catch (err) {
                console.error('Pairing Code Error:', err);
                socket.emit('status', 'Failed to generate code. Try refreshing.');
            }
        }, 5000); // সকেট স্টেবল হওয়ার জন্য ৫ সেকেন্ড অপেক্ষা
    }

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr && !phoneNumber) {
            try {
                const qrImage = await qrcode.toDataURL(qr);
                socket.emit('qr', qrImage);
                socket.emit('status', 'Scan QR Code using WhatsApp');
            } catch (err) {
                console.error('QR Generation Error:', err);
            }
        }

        if (connection === 'open') {
            socket.emit('status', 'Connected Successfully!');
            socket.emit('qr', '');
            socket.emit('pairingCode', 'CONNECTED');
            console.log('WhatsApp connected successfully!');
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            socket.emit('status', 'Connection closed, reconnecting...');
            
            if (shouldReconnect) {
                setTimeout(() => startWhatsApp(socket), 3000);
            }
        }
    });

    sock.ev.on('creds.update', saveCreds);
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'Initializing WhatsApp bot...');

    // প্রথমে নরমাল মোডে স্টার্ট করা (যাতে কিউআর বা সেশন রেডি থাকে)
    startWhatsApp(socket);

    // ইউজার যখন পেয়ারিং কোডের জন্য নম্বর পাঠাবে
    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/[^0-9]/g, '');
        if (cleanNumber.length >= 10) {
            socket.emit('status', 'Initializing pairing mode...');
            // আগের সকেট বন্ধ করে পেয়ারিং নম্বর দিয়ে নতুন করে ইনিশিয়ালাইজ করা
            if (sock) {
                try { sock.end(undefined); } catch (e) {}
            }
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
    
