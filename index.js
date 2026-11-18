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
let qrCodeData = '';
let connectionStatus = 'Disconnected';

async function startWhatsApp(socket) {
    const { state, saveCreds } = await useMultiFileAuthState('auth_session');

    sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        auth: state
    });

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect, qr } = update;

        if (qr) {
            qrCodeData = await qrcode.toDataURL(qr);
            connectionStatus = 'QR Generated';
            socket.emit('qr', qrCodeData);
            socket.emit('status', 'দয়া করে আপনার হোয়াটসঅ্যাপ দিয়ে কিউআর কোড স্ক্যান করুন');
        }

        if (connection === 'open') {
            connectionStatus = 'Connected';
            qrCodeData = '';
            socket.emit('qr', '');
            socket.emit('status', 'অভিনন্দন! হোয়াটসঅ্যাপ সফলভাবে কানেক্ট হয়েছে!');
        }

        if (connection === 'close') {
            connectionStatus = 'Disconnected';
            socket.emit('status', 'কানেকশন বিচ্ছিন্ন হয়েছে, পুনরায় চেষ্টা করা হচ্ছে...');
            startWhatsApp(socket);
        }
    });

    sock.ev.on('creds.update', saveCreds);
}

io.on('connection', (socket) => {
    console.log('একজন ইউজার ওয়েব পেজে ভিজিট করেছে');
    
    if (connectionStatus === 'Connected') {
        socket.emit('status', 'বট ইতিমধ্যে কানেক্টেড রয়েছে!');
    } else if (qrCodeData) {
        socket.emit('qr', qrCodeData);
        socket.emit('status', 'দয়া করে কিউআর কোড স্ক্যান করুন');
    } else {
        startWhatsApp(socket);
    }
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`সার্ভার চালু আছে পোর্ট ${PORT} এ`);
});
                        
