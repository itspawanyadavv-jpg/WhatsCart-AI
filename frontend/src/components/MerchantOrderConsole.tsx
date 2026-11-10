import React, { useState } from 'react';
import { BellRing, CheckCircle, CreditCard, Package, Search, Truck, XCircle } from 'lucide-react';
import type { BotOrder, BotOrderStatus } from '../types';
import { filterMerchantOrders, type OrderFilterKey } from '../utils/botHelpers.ts';

interface Props {
  orders: BotOrder[];
  allowSimulatePay: boolean;
  onSimulatePay: (orderCode: string) => Promise<void>;
  onUpdateStatus: (orderCode: string, status: BotOrderStatus, riderName?: string, otp?: string) => Promise<void>;
}

const STATUS_BADGE_CLASS: Record<BotOrderStatus, string> = {
  PLACED: 'badge-amber',
  CONFIRMED: 'badge-blue',
  PACKING: 'badge-blue',
  OUT_FOR_DELIVERY: 'badge-amber',
  DELIVERED: 'badge-green',
  CANCELLED: 'badge-red',
};

export const MerchantOrderConsole: React.FC<Props> = ({
  orders,
  allowSimulatePay,
  onSimulatePay,
  onUpdateStatus,
}) => {
  const [riderName, setRiderName] = useState('Ramesh');
  const [busyOrder, setBusyOrder] = useState<string | null>(null);
  const [filter, setFilter] = useState<OrderFilterKey>('ALL');
  const [search, setSearch] = useState('');
  const [otpInputs, setOtpInputs] = useState<Record<string, string>>({});

  const runAction = async (orderCode: string, fn: () => Promise<void>) => {
    setBusyOrder(orderCode);
    try {
      await fn();
    } finally {
      setBusyOrder(null);
    }
  };

  const filteredOrders = filterMerchantOrders(orders, filter, search);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="card" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10 }}>
          <div>
            <h3 className="card-title" style={{ marginBottom: 4 }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <BellRing size={15} color="#00a884" /> Store Owner Dispatch &amp; Automated WhatsApp Alerts
              </span>
            </h3>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
              Updating an order status here immediately sends a live WhatsApp alert to the customer.
            </div>
          </div>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
            <span style={{ color: 'var(--text-secondary)' }}>Rider Name:</span>
            <input
              value={riderName}
              onChange={(e) => setRiderName(e.target.value)}
              style={{
                background: '#0b141a',
                border: '1px solid var(--border-subtle)',
                borderRadius: 8,
                color: 'white',
                padding: '5px 9px',
                fontSize: 12,
                width: 120,
              }}
            />
          </label>
        </div>

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {(['ALL', 'ACTIVE', 'DELIVERED', 'CANCELLED'] as OrderFilterKey[]).map((key) => (
            <button
              key={key}
              type="button"
              className={`btn ${filter === key ? 'btn-primary' : ''}`}
              style={{ padding: '4px 10px', fontSize: 11.5 }}
              onClick={() => setFilter(key)}
            >
              {key}
            </button>
          ))}
          <div style={{ flex: 1, minWidth: 160, display: 'flex', alignItems: 'center', gap: 6 }}>
            <Search size={14} color="#8696a0" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search order ID, name, or phone..."
              style={{
                flex: 1,
                background: '#0b141a',
                border: '1px solid var(--border-subtle)',
                borderRadius: 8,
                color: 'white',
                padding: '5px 9px',
                fontSize: 12,
                outline: 'none',
              }}
            />
          </div>
        </div>
      </div>

      {filteredOrders.length === 0 ? (
        <div className="card" style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
          {orders.length === 0
            ? 'No WhatsApp orders placed yet. Complete a checkout in the simulator (Confirm → Address → UPI or COD) to manage orders here!'
            : 'No orders match the selected filter or search.'}
        </div>
      ) : (
        filteredOrders.map((order) => {
          const isBusy = busyOrder === order.orderCode;
          const isTerminal = order.status === 'DELIVERED' || order.status === 'CANCELLED';
          const otpVal = otpInputs[order.orderCode] ?? '';
          return (
            <div key={order.orderCode} className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <strong className="mono" style={{ fontSize: 14 }}>
                      {order.orderCode}
                    </strong>
                    <span className={`badge ${STATUS_BADGE_CLASS[order.status]}`}>
                      {order.status.replace(/_/g, ' ')}
                    </span>
                    <span className={`badge ${order.paymentStatus === 'PAID' ? 'badge-green' : 'badge-amber'}`}>
                      {order.paymentMethod} • {order.paymentStatus}
                    </span>
                    <span className="badge badge-blue mono">OTP: {order.deliveryOtp}</span>
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                    {order.customerName} ({order.phone}) • 📍 {order.deliveryAddress}
                  </div>
                </div>

                <div className="mono" style={{ fontSize: 15, fontWeight: 700, color: '#5eead4' }}>
                  ₹{Number(order.total).toFixed(2)}
                </div>
              </div>

              <div
                style={{
                  marginTop: 10,
                  padding: '8px 10px',
                  borderRadius: 8,
                  background: 'rgba(11, 20, 26, 0.55)',
                  fontSize: 12,
                  color: 'var(--text-secondary)',
                }}
              >
                {order.items.map((it) => `${it.quantity}x ${it.productName} (₹${Number(it.lineTotal).toFixed(0)})`).join(' • ')}
              </div>

              <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap', alignItems: 'center' }}>
                {order.paymentStatus === 'PENDING' && !isTerminal && allowSimulatePay && (
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={isBusy}
                    onClick={() => runAction(order.orderCode, () => onSimulatePay(order.orderCode))}
                  >
                    <CreditCard size={13} /> Simulate UPI Paid
                  </button>
                )}

                <button
                  type="button"
                  className="btn"
                  disabled={isBusy || isTerminal}
                  onClick={() => runAction(order.orderCode, () => onUpdateStatus(order.orderCode, 'PACKING', riderName))}
                >
                  <Package size={13} /> Mark Packing
                </button>

                <button
                  type="button"
                  className="btn"
                  disabled={isBusy || isTerminal}
                  onClick={() =>
                    runAction(order.orderCode, () => onUpdateStatus(order.orderCode, 'OUT_FOR_DELIVERY', riderName))
                  }
                >
                  <Truck size={13} /> Out for Delivery
                </button>

                {order.status === 'OUT_FOR_DELIVERY' && (
                  <input
                    value={otpVal}
                    onChange={(e) => setOtpInputs((prev) => ({ ...prev, [order.orderCode]: e.target.value }))}
                    placeholder="OTP (optional)"
                    maxLength={6}
                    style={{
                      width: 105,
                      background: '#0b141a',
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 8,
                      color: 'white',
                      padding: '6px 8px',
                      fontSize: 12,
                    }}
                  />
                )}

                <button
                  type="button"
                  className="btn"
                  disabled={isBusy || isTerminal}
                  onClick={() =>
                    runAction(order.orderCode, () =>
                      onUpdateStatus(order.orderCode, 'DELIVERED', riderName, otpVal)
                    )
                  }
                >
                  <CheckCircle size={13} /> Mark Delivered
                </button>

                <button
                  type="button"
                  className="btn"
                  disabled={isBusy || isTerminal}
                  style={{ color: '#fda4af', borderColor: 'rgba(244, 63, 94, 0.3)' }}
                  onClick={() => runAction(order.orderCode, () => onUpdateStatus(order.orderCode, 'CANCELLED', riderName))}
                >
                  <XCircle size={13} /> Cancel
                </button>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
};

