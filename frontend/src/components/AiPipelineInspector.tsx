import React from 'react';
import { Brain, CheckCircle2, Cpu, MapPin, ShoppingCart, Sparkles, XCircle } from 'lucide-react';
import type { ChatTurnResponse } from '../types';

interface Props {
  session: ChatTurnResponse | null;
}

export const AiPipelineInspector: React.FC<Props> = ({ session }) => {
  if (!session) {
    return <div className="card">Loading AI pipeline state...</div>;
  }

  const { parseResult, matchResults, cart, subtotal, deliveryFee, total, amountForFreeDelivery, storeRules } =
    session;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {/* Live Session & Cart Summary */}
      <div className="grid-2">
        <div className="card">
          <h3 className="card-title">
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <ShoppingCart size={15} color="#00a884" /> Active Cart &amp; Bill
            </span>
            <span className="badge badge-blue mono">{session.state}</span>
          </h3>

          {cart.length === 0 ? (
            <p style={{ margin: 0, fontSize: 12.5, color: 'var(--text-secondary)' }}>
              Cart is empty. Send a Hinglish grocery list on the left to watch live entity extraction and bill calculation.
            </p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {cart.map((item) => (
                <div
                  key={item.productId}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    fontSize: 12.5,
                    borderBottom: '1px dashed rgba(255,255,255,0.08)',
                    paddingBottom: 6,
                  }}
                >
                  <div>
                    <strong>
                      {item.quantity}x {item.productName}
                    </strong>
                    <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
                      ₹{Number(item.unitPrice).toFixed(2)} / {item.unit} • Match{' '}
                      {Math.round(item.confidence * 100)}%
                    </div>
                  </div>
                  <div className="mono" style={{ fontWeight: 600 }}>
                    ₹{(item.unitPrice * item.quantity).toFixed(2)}
                  </div>
                </div>
              ))}

              <div style={{ fontSize: 12.5, display: 'flex', flexDirection: 'column', gap: 4, paddingTop: 4 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)' }}>
                  <span>Subtotal</span>
                  <span className="mono">₹{Number(subtotal).toFixed(2)}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-secondary)' }}>
                  <span>Delivery Fee (free above ₹{storeRules.freeDeliveryAbove})</span>
                  <span className="mono" style={{ color: deliveryFee === 0 ? '#6ee7b7' : undefined }}>
                    {deliveryFee === 0 ? 'FREE' : `₹${Number(deliveryFee).toFixed(2)}`}
                  </span>
                </div>
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    fontWeight: 700,
                    fontSize: 14,
                    paddingTop: 4,
                  }}
                >
                  <span>Total Payable</span>
                  <span className="mono" style={{ color: '#5eead4' }}>
                    ₹{Number(total).toFixed(2)}
                  </span>
                </div>
                {amountForFreeDelivery > 0 && (
                  <div style={{ fontSize: 11, color: '#fcd34d', marginTop: 2 }}>
                    Add ₹{Number(amountForFreeDelivery).toFixed(0)} more to unlock free delivery.
                  </div>
                )}
              </div>
            </div>
          )}

          {session.deliveryAddress && (
            <div
              style={{
                marginTop: 10,
                padding: '7px 10px',
                borderRadius: 8,
                background: 'rgba(56, 189, 248, 0.08)',
                fontSize: 12,
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              <MapPin size={14} color="#38bdf8" />
              <span>{session.deliveryAddress}</span>
            </div>
          )}
        </div>

        {/* LLM Entity Extraction */}
        <div className="card">
          <h3 className="card-title">
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Brain size={15} color="#38bdf8" /> LLM / Hinglish NLP Extraction
            </span>
            {parseResult && (
              <span className="badge badge-green mono">{parseResult.latencyMs} ms</span>
            )}
          </h3>

          {!parseResult ? (
            <p style={{ margin: 0, fontSize: 12.5, color: 'var(--text-secondary)' }}>
              Send a message to inspect extracted intent, Hinglish number conversion, and pack-size normalization.
            </p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <span className="badge badge-blue">
                  <Cpu size={11} /> {parseResult.parserUsed}
                </span>
                <span className="badge badge-amber">Intent: {parseResult.intent}</span>
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                {parseResult.normalizedSummary}
              </div>
              <pre className="code-block">
                {JSON.stringify(
                  {
                    intent: parseResult.intent,
                    deliveryAddress: parseResult.deliveryAddress,
                    items: parseResult.items,
                  },
                  null,
                  2
                )}
              </pre>
            </div>
          )}
        </div>
      </div>

      {/* Fuzzy Catalog Matcher Results */}
      <div className="card">
        <h3 className="card-title">
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Sparkles size={15} color="#f59e0b" /> Fuzzy Catalog Matching &amp; Stock Resolution
          </span>
          <span style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>
            Synonym expansion + Levenshtein + Pack-size math
          </span>
        </h3>

        {matchResults.length === 0 ? (
          <p style={{ margin: 0, fontSize: 12.5, color: 'var(--text-secondary)' }}>
            Item-level match scores and stock checks will appear here when you order groceries.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {matchResults.map((m, idx) => (
              <div
                key={`${m.rawQuery}-${idx}`}
                style={{
                  padding: '10px 12px',
                  borderRadius: 10,
                  background: 'rgba(11, 20, 26, 0.5)',
                  border: '1px solid var(--border-subtle)',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 6,
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    {m.matched && !m.outOfStock ? (
                      <CheckCircle2 size={16} color="#10b981" />
                    ) : (
                      <XCircle size={16} color="#f43f5e" />
                    )}
                    <span className="mono" style={{ fontSize: 12, color: '#fcd34d' }}>
                      &quot;{m.rawQuery}&quot;
                    </span>
                    <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>→</span>
                    <strong style={{ fontSize: 13 }}>
                      {m.matchedProductName ?? 'No match found'}
                    </strong>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    {m.matched && !m.outOfStock && (
                      <span className="badge badge-green mono">
                        Qty: {m.requestedQty} • ₹{Number(m.unitPrice).toFixed(2)}
                      </span>
                    )}
                    {m.outOfStock && <span className="badge badge-red">Out of Stock</span>}
                    <span className="badge badge-blue mono">
                      {Math.round(m.confidence * 100)}%
                    </span>
                  </div>
                </div>

                <div className="confidence-track">
                  <div
                    className="confidence-fill"
                    style={{ width: `${Math.min(100, Math.round(m.confidence * 100))}%` }}
                  />
                </div>

                <div style={{ fontSize: 11.5, color: 'var(--text-secondary)' }}>
                  {m.matchReason}
                  {m.alternatives && m.alternatives.length > 0 && !m.matched && (
                    <span> • Suggestions: {m.alternatives.join(', ')}</span>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

