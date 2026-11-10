import React, { useState } from 'react';
import { KeyRound, X } from 'lucide-react';
import { getStoredAdminKey, setStoredAdminKey } from '../api';

interface Props {
  onClose: () => void;
}

export const WebhookDocsModal: React.FC<Props> = ({ onClose }) => {
  const [adminKey, setAdminKey] = useState(getStoredAdminKey);
  const [savedNotice, setSavedNotice] = useState(false);

  const handleSaveKey = (e: React.FormEvent) => {
    e.preventDefault();
    setStoredAdminKey(adminKey);
    setSavedNotice(true);
    setTimeout(() => setSavedNotice(false), 2000);
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
          <h2 style={{ margin: 0, fontSize: 18 }}>Production Webhooks &amp; Security Configuration</h2>
          <button type="button" className="btn" onClick={onClose}>
            <X size={15} /> Close
          </button>
        </div>

        <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 0 }}>
          This backend exposes real production webhooks alongside the interactive browser simulator. Configure keys in{' '}
          <span className="mono">.env</span> (see <span className="mono">.env.example</span>) and expose port{' '}
          <span className="mono">8090</span> via ngrok, Cloudflare Tunnel, or AWS EC2.
        </p>

        <div className="card" style={{ marginBottom: 12 }}>
          <h3 className="card-title">1. Meta WhatsApp Cloud API Webhook</h3>
          <div style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
            Verify Endpoint: <span className="mono">GET /api/webhooks/whatsapp</span> (checks{' '}
            <span className="mono">WHATSAPP_VERIFY_TOKEN</span>)
            <br />
            Inbound Messages: <span className="mono">POST /api/webhooks/whatsapp</span> (supports text, interactive
            replies, and location pins; verifies <span className="mono">X-Hub-Signature-256</span>)
          </div>
          <pre className="code-block">{`curl -X POST http://localhost:8090/api/webhooks/whatsapp \\
  -H "Content-Type: application/json" \\
  -d '{
    "entry": [{ "changes": [{ "value": {
      "contacts": [{"wa_id": "919876543210", "profile": {"name": "Aman"}}],
      "messages": [{"from": "919876543210", "type": "text", "text": {"body": "2 packet Amul milk aur 5kg Aashirvaad atta bhejo"}}]
    }}]}]
  }'`}</pre>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <h3 className="card-title">2. Twilio WhatsApp Sandbox Webhook</h3>
          <div style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
            Endpoint: <span className="mono">POST /api/webhooks/twilio</span> (verifies{' '}
            <span className="mono">X-Twilio-Signature</span> when configured and returns TwiML XML)
          </div>
          <pre className="code-block">{`curl -X POST http://localhost:8090/api/webhooks/twilio \\
  -d "From=whatsapp:+919876543210" \\
  -d "ProfileName=Aman" \\
  -d "Body=1 Tata salt aur 2 Parle-G bhejo"`}</pre>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <h3 className="card-title">3. Razorpay Payment Link &amp; Callback</h3>
          <div style={{ fontSize: 12.5, color: 'var(--text-secondary)' }}>
            Webhook: <span className="mono">POST /api/webhooks/razorpay</span> (verifies{' '}
            <span className="mono">X-Razorpay-Signature</span> for <span className="mono">payment_link.paid</span>,{' '}
            <span className="mono">payment_link.expired</span>, and <span className="mono">payment.failed</span>)
            <br />
            Redirect Callback Page: <span className="mono">GET /api/payments/callback?orderCode=...</span>
          </div>
        </div>

        <div className="card">
          <h3 className="card-title">
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <KeyRound size={14} color="#f59e0b" /> 4. Optional Admin API Key (Merchant &amp; Sync Auth)
            </span>
          </h3>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 8 }}>
            If <span className="mono">ADMIN_API_KEY</span> is set on the backend, enter it here so this browser UI
            includes <span className="mono">X-Admin-Key</span> on dispatch &amp; catalog sync calls.
          </div>
          <form onSubmit={handleSaveKey} style={{ display: 'flex', gap: 8 }}>
            <input
              type="password"
              value={adminKey}
              onChange={(e) => setAdminKey(e.target.value)}
              placeholder="Optional X-Admin-Key"
              style={{
                flex: 1,
                background: '#0b141a',
                border: '1px solid var(--border-subtle)',
                borderRadius: 8,
                color: 'white',
                padding: '6px 10px',
                fontSize: 12.5,
              }}
            />
            <button type="submit" className="btn btn-primary">
              {savedNotice ? 'Saved!' : 'Save Key'}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
};

