import type { BotOrder } from '../types.ts';

export type OrderFilterKey = 'ALL' | 'ACTIVE' | 'DELIVERED' | 'CANCELLED';

export interface ParsedMeta {
  quickReplies?: string[];
  paymentCard?: {
    orderCode: string;
    amount: number;
    shortUrl: string;
    upiDeepLink: string;
    liveRazorpay: boolean;
  };
}

export interface TextSegment {
  kind: 'text' | 'bold' | 'italic';
  value: string;
}

export function splitWhatsAppFormatting(text: string): TextSegment[] {
  const tokens = text.split(/(\*[^*\n]+\*|_[^_\n]+_)/g);
  return tokens
    .filter((part) => part.length > 0)
    .map((part) => {
      if (part.startsWith('*') && part.endsWith('*') && part.length > 2) {
        return { kind: 'bold', value: part.slice(1, -1) };
      }
      if (part.startsWith('_') && part.endsWith('_') && part.length > 2) {
        return { kind: 'italic', value: part.slice(1, -1) };
      }
      return { kind: 'text', value: part };
    });
}

export function parseMessageMeta(raw: string | null): ParsedMeta {
  if (!raw) return {};
  try {
    return JSON.parse(raw) as ParsedMeta;
  } catch {
    return {};
  }
}

export function filterMerchantOrders(
  orders: BotOrder[],
  filter: OrderFilterKey,
  search: string
): BotOrder[] {
  const q = search.trim().toLowerCase();
  return orders.filter((order) => {
    if (filter === 'ACTIVE' && (order.status === 'DELIVERED' || order.status === 'CANCELLED')) {
      return false;
    }
    if (filter === 'DELIVERED' && order.status !== 'DELIVERED') {
      return false;
    }
    if (filter === 'CANCELLED' && order.status !== 'CANCELLED') {
      return false;
    }
    if (!q) return true;
    return (
      order.orderCode.toLowerCase().includes(q) ||
      (order.customerName || '').toLowerCase().includes(q) ||
      order.phone.toLowerCase().includes(q)
    );
  });
}

