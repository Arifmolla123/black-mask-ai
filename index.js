const express = require('express');
const { default: makeWASocket, useMultiFileAuthState, DisconnectReason, Browsers, fetchLatestBaileysVersion } = require('@whiskeysockets/baileys');
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
    try {
        const { state, saveCreds } = await useMultiFileAuthState('auth_session');
        const { version } = await fetchLatestBaileysVersion();

        sock = makeWASocket({
            version,
            logger: pino({ level: 'silent' }),
            auth: state,
            printQRInTerminal: false,
            browser: Browsers.macOS('Chrome')
        });

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

            // সকেট কানেকশন ইনিশিয়ালাইজ হওয়ার পর যদি ফোন নম্বর থাকে তবে পেয়ারিং কোড তুলবে
            if (connection === 'connecting' && phoneNumber && !sock.authState.creds.registered) {
                try {
                    socket.emit('status', 'Requesting pairing code...');
                    // একটু সময় দিয়ে সকেট স্টেবল করা
                    await new Promise(resolve => setTimeout(resolve, 3000));
                    let code = await sock.requestPairingCode(phoneNumber);
                    code = code?.match(/.{1,4}/g)?.join('-') || code;
                    socket.emit('pairingCode', code);
                    socket.emit('status', 'Enter this code in your WhatsApp app!');
                } catch (err) {
                    console.error('Pairing Code Error:', err);
                    socket.emit('status', 'Failed to generate pairing code. Try again.');
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

    } catch (error) {
        console.error('WhatsApp Init Error:', error);
        socket.emit('status', 'Initialization error occurred!');
    }
}

io.on('connection', (socket) => {
    console.log('New client connected to web UI');
    socket.emit('status', 'Initializing WhatsApp bot...');

    // প্রথমে ডিফল্টভাবে কিউআর কোডের জন্য স্টার্ট করা
    startWhatsApp(socket);

    socket.on('request-pairing-code', (phoneNumber) => {
        const cleanNumber = phoneNumber.replace(/[^0-9]/g, '');
        if (cleanNumber.length >= 10) {
            // নতুন নম্বর দিয়ে সকেট পুনরায় ইনিশিয়ালাইজ করা
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
            
