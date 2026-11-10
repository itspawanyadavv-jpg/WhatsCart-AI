import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  filterMerchantOrders,
  parseMessageMeta,
  splitWhatsAppFormatting,
} from './botHelpers.ts';
import type { BotOrder } from '../types.ts';

describe('botHelpers', () => {
  it('parses bold and italic WhatsApp formatting segments', () => {
    const segments = splitWhatsAppFormatting('Order *DRC-WA-1234* _confirmed_ today');
    assert.deepEqual(segments, [
      { kind: 'text', value: 'Order ' },
      { kind: 'bold', value: 'DRC-WA-1234' },
      { kind: 'text', value: ' ' },
      { kind: 'italic', value: 'confirmed' },
      { kind: 'text', value: ' today' },
    ]);
  });

  it('safely parses valid and malformed message metadata JSON', () => {
    assert.deepEqual(parseMessageMeta(null), {});
    assert.deepEqual(parseMessageMeta('{bad-json'), {});
    const meta = parseMessageMeta('{"quickReplies":["Pay via UPI","COD"]}');
    assert.deepEqual(meta.quickReplies, ['Pay via UPI', 'COD']);
  });

  it('filters merchant orders by status category and search query', () => {
    const base = {
      deliveryAddress: 'Sector 12',
      paymentMethod: 'UPI_LINK',
      paymentStatus: 'PAID' as const,
      subtotal: 100,
      deliveryFee: 0,
      total: 100,
      razorpayPaymentLinkId: null,
      razorpayPaymentLinkUrl: null,
      razorpayPaymentId: null,
      upiDeepLink: null,
      channel: 'SIMULATOR',
      deliveryOtp: '1234',
      items: [] as BotOrder['items'],
      createdAt: '2026-11-10T10:00:00',
      updatedAt: '2026-11-10T10:00:00',
    };

    const orders: BotOrder[] = [
      { ...base, id: 1, orderCode: 'DRC-WA-1001', phone: '+919876543210', customerName: 'Aman', status: 'PACKING' },
      { ...base, id: 2, orderCode: 'DRC-WA-1002', phone: '+919811112222', customerName: 'Priya', status: 'DELIVERED' },
      { ...base, id: 3, orderCode: 'DRC-WA-1003', phone: '+919822223333', customerName: 'Raj', status: 'CANCELLED' },
    ];

    assert.equal(filterMerchantOrders(orders, 'ACTIVE', '').length, 1);
    assert.equal(filterMerchantOrders(orders, 'DELIVERED', '').length, 1);
    assert.equal(filterMerchantOrders(orders, 'CANCELLED', '').length, 1);
    assert.equal(filterMerchantOrders(orders, 'ALL', 'priya')[0]?.orderCode, 'DRC-WA-1002');
    assert.equal(filterMerchantOrders(orders, 'ALL', '1003')[0]?.customerName, 'Raj');
  });
});

