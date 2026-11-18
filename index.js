const { default: makeWASocket, useMultiFileAuthState, DisconnectReason } = require('@whiskeysockets/baileys');
const { Boom } = require('@hapi/boom');
const pino = require('pino');
const qrcode = require('qrcode-terminal');

async function connectToWhatsApp() {
    // সেশন সেভ করার ফোল্ডার
    const { state, saveCreds } = await useMultiFileAuthState('auth_info_baileys');

    const sock = makeWASocket({
        logger: pino({ level: 'silent' }),
        printQRInTerminal: true,
        auth: state
    });

    // কিউআর কোড জেনারেশন ও কানেকশন স্ট্যাটাস
    sock.ev.on('connection.update', (update) => {
        const { connection, lastDisconnect, qr } = update;
        
        if (qr) {
            console.log('নিচের কিউআর কোডটি আপনার হোয়াটসঅ্যাপ দিয়ে স্ক্যান করুন:');
            qrcode.generate(qr, { small: true });
        }

        if (connection === 'close') {
            const shouldReconnect = (lastDisconnect?.error instanceof Boom)?.output?.statusCode !== DisconnectReason.loggedOut;
            console.log('কানেকশন বিচ্ছিন্ন হয়েছে, পুনরায় সংযোগ করা হচ্ছে...', shouldReconnect);
            if (shouldReconnect) {
                connectToWhatsApp();
            }
        } else if (connection === 'open') {
            console.log('অভিনন্দন! হোয়াটসঅ্যাপ বট সফলভাবে কানেক্ট হয়েছে এবং সচল রয়েছে!');
        }
    });

    // ক্রেডেনশিয়াল আপডেট সেভ করা
    sock.ev.on('creds.update', saveCreds);

    // মেসেজ হ্যান্ডলিং (কেউ মেসেজ দিলে এখানে রিসিভ হবে)
    sock.ev.on('messages.upsert', async ({ messages }) => {
        const msg = messages[0];
        if (!msg.message || msg.key.fromMe) return;

        const sender = msg.key.remoteJid;
        const messageText = msg.message.conversation || msg.message.extendedTextMessage?.text;

        console.log(`টেক্সট পাওয়া গেছে: ${messageText} (প্রেরক: ${sender})`);

        // উদাহরণস্বরূপ অটো-রিপ্লাই
        if (messageText && messageText.toLowerCase() === 'hi') {
            await sock.sendMessage(sender, { text: 'হ্যালো! আমি আপনার পাইথন/নোটবট, বলুন আপনাকে কীভাবে সাহায্য করতে পারি?' });
        }
    });
}

connectToWhatsApp();
              
