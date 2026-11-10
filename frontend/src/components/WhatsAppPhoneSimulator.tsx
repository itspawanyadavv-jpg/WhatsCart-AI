import React, { useEffect, useRef, useState } from 'react';
import {
  CheckCheck,
  CreditCard,
  ExternalLink,
  RefreshCw,
  Send,
  ShieldCheck,
  Sparkles,
  UserCheck,
} from 'lucide-react';
import type { BotOrder, ChatMessage } from '../types';
import { parseMessageMeta, splitWhatsAppFormatting } from '../utils/botHelpers.ts';

interface Props {
  phone: string;
  customerName: string;
  messages: ChatMessage[];
  activeOrder: BotOrder | null;
  sending: boolean;
  allowSimulatePay: boolean;
  onSend: (text: string) => void;
  onReset: () => void;
  onSimulatePay: (orderCode: string) => void;
  onChangeProfile: (phone: string, customerName: string) => void;
}

const DEMO_PROFILES = [
  { name: 'Aman Sharma', phone: '+919876543210' },
  { name: 'Priya Verma', phone: '+919811112222' },
  { name: 'Rajesh Gupta', phone: '+919811113333' },
];

const HINGLISH_PRESETS = [
  {
    label: '🥛 Milk + Bread + 5kg Atta',
    text: 'Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do',
  },
  {
    label: '🛢️ 2 Fortune Oil + Ek Namak',
    text: 'do bottle fortune tel aur ek tata namak bhejo at Flat 402, Krishna Heights, Sector 12',
  },
  {
    label: '☕ Chai + Makkhan + Biscuits',
    text: '1 Tata chai, 1 Amul butter, aur 2 Parle-G bhej do',
  },
  {
    label: '📋 Catalog Dikhao',
    text: 'Catalog dikhao',
  },
  {
    label: '🔁 Repeat Last Order',
    text: 'Repeat last order',
  },
  {
    label: '📜 My Orders',
    text: 'My orders',
  },
];

function formatWhatsAppText(text: string): React.ReactNode[] {
  return splitWhatsAppFormatting(text).map((seg, idx) => {
    if (seg.kind === 'bold') return <strong key={idx}>{seg.value}</strong>;
    if (seg.kind === 'italic') return <em key={idx}>{seg.value}</em>;
    return <React.Fragment key={idx}>{seg.value}</React.Fragment>;
  });
}

export const WhatsAppPhoneSimulator: React.FC<Props> = ({
  phone,
  customerName,
  messages,
  activeOrder,
  sending,
  allowSimulatePay,
  onSend,
  onReset,
  onSimulatePay,
  onChangeProfile,
}) => {
  const [input, setInput] = useState('');
  const [showCustomProfile, setShowCustomProfile] = useState(false);
  const [customPhone, setCustomPhone] = useState(phone);
  const [customName, setCustomName] = useState(customerName);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length, sending]);

  useEffect(() => {
    setCustomPhone(phone);
    setCustomName(customerName);
  }, [phone, customerName]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = input.trim();
    if (!trimmed || sending) return;
    setInput('');
    onSend(trimmed);
  };

  const handleApplyCustomProfile = (e: React.FormEvent) => {
    e.preventDefault();
    if (!customPhone.trim()) return;
    onChangeProfile(customPhone.trim(), customName.trim() || 'WhatsApp Customer');
    setShowCustomProfile(false);
  };

  const lastOutboundId = [...messages].reverse().find((m) => m.direction === 'OUTBOUND')?.id;

  return (
    <div className="phone-frame">
      <div className="wa-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div className="wa-avatar">DRC</div>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontWeight: 700, fontSize: 14 }}>
              DRC Krishna Gen Store
              <ShieldCheck size={15} color="#5eead4" />
            </div>
            <div style={{ fontSize: 11, opacity: 0.85 }}>
              WhatsApp AI Bot • {customerName} ({phone})
            </div>
          </div>
        </div>
        <button
          type="button"
          onClick={onReset}
          className="btn"
          title="Reset simulator conversation"
          style={{
            background: 'rgba(255,255,255,0.14)',
            borderColor: 'rgba(255,255,255,0.22)',
            color: 'white',
            padding: '5px 10px',
            fontSize: 11.5,
          }}
        >
          <RefreshCw size={13} /> Reset
        </button>
      </div>

      {/* Customer profile switcher */}
      <div
        style={{
          background: '#0a4f46',
          padding: '5px 10px',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          flexWrap: 'wrap',
          borderTop: '1px solid rgba(255,255,255,0.12)',
        }}
      >
        <span style={{ fontSize: 10.5, color: '#ccfbf1', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <UserCheck size={12} /> Customer:
        </span>
        {DEMO_PROFILES.map((p) => {
          const active = p.phone === phone;
          return (
            <button
              key={p.phone}
              type="button"
              onClick={() => onChangeProfile(p.phone, p.name)}
              disabled={sending}
              style={{
                border: '1px solid rgba(255,255,255,0.25)',
                background: active ? '#25d366' : 'rgba(255,255,255,0.1)',
                color: active ? '#052e16' : 'white',
                borderRadius: 999,
                padding: '2px 8px',
                fontSize: 10.5,
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              {p.name.split(' ')[0]}
            </button>
          );
        })}
        <button
          type="button"
          onClick={() => setShowCustomProfile((v) => !v)}
          style={{
            marginLeft: 'auto',
            border: 'none',
            background: 'transparent',
            color: '#99f6e4',
            fontSize: 10.5,
            cursor: 'pointer',
            textDecoration: 'underline',
          }}
        >
          {showCustomProfile ? 'Close' : 'Custom #'}
        </button>
      </div>

      {showCustomProfile && (
        <form
          onSubmit={handleApplyCustomProfile}
          style={{
            background: '#e2e8f0',
            padding: '6px 10px',
            display: 'flex',
            gap: 6,
            alignItems: 'center',
          }}
        >
          <input
            value={customName}
            onChange={(e) => setCustomName(e.target.value)}
            placeholder="Name"
            style={{ width: 105, padding: '4px 7px', borderRadius: 6, border: '1px solid #cbd5e1', fontSize: 11.5 }}
          />
          <input
            value={customPhone}
            onChange={(e) => setCustomPhone(e.target.value)}
            placeholder="+9198..."
            style={{ flex: 1, padding: '4px 7px', borderRadius: 6, border: '1px solid #cbd5e1', fontSize: 11.5 }}
          />
          <button type="submit" className="btn btn-primary" style={{ padding: '4px 9px', fontSize: 11 }}>
            Switch
          </button>
        </form>
      )}

      <div className="wa-chat-canvas">
        <div
          style={{
            alignSelf: 'center',
            background: '#fff3cd',
            color: '#543b06',
            fontSize: 11,
            padding: '5px 10px',
            borderRadius: 8,
            boxShadow: '0 1px 1px rgba(0,0,0,0.08)',
            textAlign: 'center',
          }}
        >
          🔒 End-to-end WhatsApp Ordering Simulator • Try Hinglish grocery lists below
        </div>

        {messages.map((msg) => {
          const isInbound = msg.direction === 'INBOUND';
          const meta = parseMessageMeta(msg.metadataJson);
          const timeStr = new Date(msg.createdAt).toLocaleTimeString([], {
            hour: '2-digit',
            minute: '2-digit',
          });
          const isPaidOrder =
            meta.paymentCard &&
            activeOrder?.orderCode === meta.paymentCard.orderCode &&
            activeOrder?.paymentStatus === 'PAID';

          return (
            <React.Fragment key={msg.id}>
              <div className={`wa-bubble ${isInbound ? 'inbound' : 'outbound'}`}>
                <div>{formatWhatsAppText(msg.body)}</div>

                {meta.paymentCard && (
                  <div className="wa-payment-card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ fontWeight: 700, fontSize: 12.5, color: '#065f46' }}>
                        <CreditCard size={14} style={{ verticalAlign: 'middle', marginRight: 5 }} />
                        Razorpay UPI Checkout
                      </span>
                      <span className="mono" style={{ fontWeight: 700, fontSize: 12.5, color: '#047857' }}>
                        ₹{Number(meta.paymentCard.amount).toFixed(2)}
                      </span>
                    </div>

                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {!isPaidOrder ? (
                        <>
                          {meta.paymentCard.liveRazorpay && meta.paymentCard.shortUrl && (
                            <a
                              href={meta.paymentCard.shortUrl}
                              target="_blank"
                              rel="noreferrer"
                              className="btn btn-primary"
                              style={{ fontSize: 12, padding: '6px 10px', flex: 1, textDecoration: 'none' }}
                            >
                              Pay on Razorpay <ExternalLink size={12} />
                            </a>
                          )}
                          {allowSimulatePay && (
                            <button
                              type="button"
                              onClick={() => onSimulatePay(meta.paymentCard!.orderCode)}
                              className="btn btn-primary"
                              style={{ fontSize: 12, padding: '6px 10px', flex: 1 }}
                            >
                              ⚡ Simulate UPI Paid
                            </button>
                          )}
                        </>
                      ) : (
                        <span
                          style={{
                            fontSize: 12,
                            fontWeight: 700,
                            color: '#047857',
                            padding: '4px 0',
                          }}
                        >
                          ✅ Paid Successfully
                        </span>
                      )}

                      <a
                        href={meta.paymentCard.upiDeepLink}
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: 4,
                          fontSize: 11.5,
                          fontWeight: 600,
                          color: '#0369a1',
                          textDecoration: 'none',
                          padding: '6px 8px',
                          borderRadius: 8,
                          background: '#e0f2fe',
                        }}
                      >
                        UPI App <ExternalLink size={12} />
                      </a>
                    </div>
                  </div>
                )}

                <div className="wa-bubble-meta">
                  <span>{timeStr}</span>
                  {isInbound && <CheckCheck size={14} color="#53bdeb" />}
                </div>
              </div>

              {msg.id === lastOutboundId && meta.quickReplies && meta.quickReplies.length > 0 && (
                <div className="wa-quick-replies">
                  {meta.quickReplies.map((qr) => (
                    <button
                      key={qr}
                      type="button"
                      className="wa-quick-chip"
                      disabled={sending}
                      onClick={() => onSend(qr)}
                    >
                      {qr}
                    </button>
                  ))}
                </div>
              )}
            </React.Fragment>
          );
        })}

        {sending && (
          <div className="wa-bubble outbound" style={{ color: '#667781', fontStyle: 'italic' }}>
            Bot is parsing Hinglish items...
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      <div className="wa-presets-bar">
        <span style={{ fontSize: 11, fontWeight: 600, color: '#475569', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <Sparkles size={12} color="#008069" /> Try:
        </span>
        {HINGLISH_PRESETS.map((preset) => (
          <button
            key={preset.label}
            type="button"
            className="preset-chip"
            disabled={sending}
            onClick={() => onSend(preset.text)}
          >
            {preset.label}
          </button>
        ))}
      </div>

      <form className="wa-input-bar" onSubmit={handleSubmit}>
        <input
          className="wa-input"
          placeholder="Type Hinglish order (e.g. 2 doodh aur 5kg atta bhejo)..."
          value={input}
          onChange={(e) => setInput(e.target.value)}
          disabled={sending}
        />
        <button type="submit" className="wa-send-btn" disabled={sending || !input.trim()} aria-label="Send message">
          <Send size={17} />
        </button>
      </form>
    </div>
  );
};

