import type {
  BotOrder,
  BotOrderStatus,
  CatalogProduct,
  ChatTurnResponse,
  HealthInfo,
  StoreRulesDto,
} from './types';

const ADMIN_KEY_STORAGE = 'drc_bot_admin_key';

export function getStoredAdminKey(): string {
  try {
    return localStorage.getItem(ADMIN_KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

export function setStoredAdminKey(key: string): void {
  try {
    if (key.trim()) {
      localStorage.setItem(ADMIN_KEY_STORAGE, key.trim());
    } else {
      localStorage.removeItem(ADMIN_KEY_STORAGE);
    }
  } catch {
    // Ignore storage unavailability
  }
}

async function request<T>(url: string, options?: RequestInit, withAdminKey = false): Promise<T> {
  const adminKey = withAdminKey ? getStoredAdminKey() : '';
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> | undefined),
  };
  if (adminKey) {
    headers['X-Admin-Key'] = adminKey;
  }

  const res = await fetch(url, {
    ...options,
    headers,
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(err.message || err.error || `Request failed (${res.status})`);
  }
  return res.json() as Promise<T>;
}

export const api = {
  getHealth: () => request<HealthInfo>('/api/health'),

  getSession: (phone: string) =>
    request<ChatTurnResponse>(`/api/simulator/session?phone=${encodeURIComponent(phone)}`),

  sendMessage: (phone: string, customerName: string, message: string) =>
    request<ChatTurnResponse>('/api/simulator/message', {
      method: 'POST',
      body: JSON.stringify({ phone, customerName, message }),
    }),

  resetChat: (phone: string, customerName: string) =>
    request<ChatTurnResponse>(
      `/api/simulator/reset?phone=${encodeURIComponent(phone)}&customerName=${encodeURIComponent(customerName)}`,
      { method: 'POST' }
    ),

  getCatalog: () =>
    request<{
      products: CatalogProduct[];
      storeRules: StoreRulesDto;
      drcApiBaseUrl: string;
      lastLiveSyncOk: boolean;
      lastSyncedAt: string;
    }>('/api/catalog'),

  syncCatalog: (includeLocalStaples: boolean) =>
    request<{
      liveSyncSucceeded: boolean;
      drcApiBaseUrl: string;
      liveDrcCount: number;
      localSupplementCount: number;
      totalCatalogCount: number;
      storeRules: StoreRulesDto;
      message: string;
    }>(
      `/api/catalog/sync?includeLocalStaples=${includeLocalStaples}`,
      { method: 'POST' },
      true
    ),

  getOrders: () => request<BotOrder[]>('/api/orders', undefined, true),

  simulatePay: (orderCode: string) =>
    request<BotOrder>(
      `/api/orders/${encodeURIComponent(orderCode)}/simulate-pay`,
      { method: 'POST' },
      true
    ),

  updateOrderStatus: (orderCode: string, status: BotOrderStatus, riderName?: string, otp?: string) =>
    request<BotOrder>(
      `/api/orders/${encodeURIComponent(orderCode)}/status`,
      {
        method: 'PATCH',
        body: JSON.stringify({ status, riderName, otp: otp?.trim() ? otp.trim() : undefined }),
      },
      true
    ),
};

