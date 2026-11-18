import os
from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/')
def home():
    return "WhatsApp Python Bot is Alive and Running!"

@app.route('/webhook', methods=['POST'])
def webhook():
    data = request.json
    print("Received message data:", data)
    
    # এখানে তোমার বটের অটো-রিপ্লাই বা এআই লজিক যুক্ত করতে হবে
    
    return jsonify({"status": "success"}), 200

if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    app.run(host='0.0.0.0', port=port)
  
