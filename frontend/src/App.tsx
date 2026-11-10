import { useCallback, useEffect, useState } from 'react';
import {
  Activity,
  Bot,
  Code2,
  PackageCheck,
  Store,
  Webhook,
} from 'lucide-react';
import { api, getStoredAdminKey } from './api';
import { AiPipelineInspector } from './components/AiPipelineInspector';
import { CatalogAndSyncPanel } from './components/CatalogAndSyncPanel';
import { MerchantOrderConsole } from './components/MerchantOrderConsole';
import { WebhookDocsModal } from './components/WebhookDocsModal';
import { WhatsAppPhoneSimulator } from './components/WhatsAppPhoneSimulator';
import type {
  BotOrder,
  BotOrderStatus,
  CatalogProduct,
  ChatMessage,
  ChatTurnResponse,
  HealthInfo,
  StoreRulesDto,
} from './types';

type TabKey = 'inspector' | 'merchant' | 'catalog';

const DEFAULT_PHONE = '+919876543210';
const DEFAULT_CUSTOMER = 'Aman Sharma';

export default function App() {
  const [activeTab, setActiveTab] = useState<TabKey>('inspector');
  const [phone, setPhone] = useState(DEFAULT_PHONE);
  const [customerName, setCustomerName] = useState(DEFAULT_CUSTOMER);
  const [health, setHealth] = useState<HealthInfo | null>(null);
  const [session, setSession] = useState<ChatTurnResponse | null>(null);
  const [orders, setOrders] = useState<BotOrder[]>([]);
  const [catalog, setCatalog] = useState<CatalogProduct[]>([]);
  const [storeRules, setStoreRules] = useState<StoreRulesDto | null>(null);
  const [drcApiBaseUrl, setDrcApiBaseUrl] = useState('https://13-206-156-137.sslip.io');
  const [lastLiveSyncOk, setLastLiveSyncOk] = useState(false);
  const [lastSyncMessage, setLastSyncMessage] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [showWebhookDocs, setShowWebhookDocs] = useState(false);
  const [errorBanner, setErrorBanner] = useState<string | null>(null);

  const loadSessionFor = useCallback(async (targetPhone: string, targetName: string) => {
    const sess = await api.getSession(targetPhone);
    if (sess.messages.length === 0) {
      const fresh = await api.resetChat(targetPhone, targetName);
      setSession(fresh);
    } else {
      setSession(sess);
    }
  }, []);

  const loadAll = useCallback(async () => {
    try {
      const [h, ordList, catData] = await Promise.all([
        api.getHealth(),
        api.getOrders(),
        api.getCatalog(),
      ]);
      setHealth(h);
      setOrders(ordList);
      setCatalog(catData.products);
      setStoreRules(catData.storeRules);
      setDrcApiBaseUrl(catData.drcApiBaseUrl);
      setLastLiveSyncOk(catData.lastLiveSyncOk);
      await loadSessionFor(DEFAULT_PHONE, DEFAULT_CUSTOMER);
      setErrorBanner(null);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Backend connection error');
    }
  }, [loadSessionFor]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  // Real-time SSE stream for active simulator customer chat
  useEffect(() => {
    const chatSource = new EventSource(`/api/simulator/stream?phone=${encodeURIComponent(phone)}`);
    chatSource.addEventListener('message', (event) => {
      try {
        const incoming = JSON.parse((event as MessageEvent).data) as ChatMessage;
        setSession((prev) => {
          if (!prev) return prev;
          if (prev.messages.some((m) => m.id === incoming.id)) return prev;
          return { ...prev, messages: [...prev.messages, incoming] };
        });
      } catch {
        // Ignore malformed SSE event
      }
    });

    chatSource.addEventListener('order_update', (event) => {
      try {
        const updatedOrder = JSON.parse((event as MessageEvent).data) as BotOrder;
        setSession((prev) => (prev ? { ...prev, activeOrder: updatedOrder } : prev));
      } catch {
        // Ignore malformed SSE event
      }
    });

    return () => {
      chatSource.close();
    };
  }, [phone]);

  // Real-time SSE stream for merchant dispatch console
  useEffect(() => {
    const adminKey = getStoredAdminKey();
    const streamUrl = adminKey
      ? `/api/orders/stream?adminKey=${encodeURIComponent(adminKey)}`
      : '/api/orders/stream';
    const merchantSource = new EventSource(streamUrl);
    merchantSource.addEventListener('order_update', (event) => {
      try {
        const updated = JSON.parse((event as MessageEvent).data) as BotOrder;
        setOrders((prev) => {
          const exists = prev.some((o) => o.orderCode === updated.orderCode);
          if (exists) {
            return prev.map((o) => (o.orderCode === updated.orderCode ? updated : o));
          }
          return [updated, ...prev];
        });
      } catch {
        // Ignore
      }
    });

    return () => {
      merchantSource.close();
    };
  }, []);

  const refreshCatalogAndOrders = async () => {
    const [ordList, catData, h] = await Promise.all([api.getOrders(), api.getCatalog(), api.getHealth()]);
    setOrders(ordList);
    setCatalog(catData.products);
    setStoreRules(catData.storeRules);
    setHealth(h);
  };

  const handleSend = async (text: string) => {
    setSending(true);
    setErrorBanner(null);
    try {
      const updated = await api.sendMessage(phone, customerName, text);
      setSession(updated);
      await refreshCatalogAndOrders();
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to send message');
    } finally {
      setSending(false);
    }
  };

  const handleReset = async () => {
    setSending(true);
    setErrorBanner(null);
    try {
      const fresh = await api.resetChat(phone, customerName);
      setSession(fresh);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to reset chat');
    } finally {
      setSending(false);
    }
  };

  const handleChangeProfile = async (nextPhone: string, nextName: string) => {
    setSending(true);
    setErrorBanner(null);
    setPhone(nextPhone);
    setCustomerName(nextName);
    try {
      await loadSessionFor(nextPhone, nextName);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to switch customer profile');
    } finally {
      setSending(false);
    }
  };

  const handleSimulatePay = async (orderCode: string) => {
    setErrorBanner(null);
    try {
      const updatedOrder = await api.simulatePay(orderCode);
      setOrders((prev) => prev.map((o) => (o.orderCode === updatedOrder.orderCode ? updatedOrder : o)));
      setSession((prev) => (prev ? { ...prev, activeOrder: updatedOrder } : prev));
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to simulate payment');
    }
  };

  const handleUpdateOrderStatus = async (
    orderCode: string,
    status: BotOrderStatus,
    riderName?: string,
    otp?: string
  ) => {
    setErrorBanner(null);
    try {
      const updatedOrder = await api.updateOrderStatus(orderCode, status, riderName, otp);
      setOrders((prev) => prev.map((o) => (o.orderCode === updatedOrder.orderCode ? updatedOrder : o)));
      setSession((prev) => (prev ? { ...prev, activeOrder: updatedOrder } : prev));
      if (status === 'CANCELLED') {
        const catData = await api.getCatalog();
        setCatalog(catData.products);
      }
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to update order status');
    }
  };

  const handleSyncCatalog = async (includeLocalStaples: boolean) => {
    setErrorBanner(null);
    try {
      const res = await api.syncCatalog(includeLocalStaples);
      const catData = await api.getCatalog();
      setCatalog(catData.products);
      setStoreRules(res.storeRules);
      setLastLiveSyncOk(res.liveSyncSucceeded);
      setLastSyncMessage(res.message);
      const h = await api.getHealth();
      setHealth(h);
    } catch (err) {
      setErrorBanner(err instanceof Error ? err.message : 'Failed to sync catalog');
    }
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-icon">
            <Bot size={22} />
          </div>
          <div>
            <h1>DRC WhatsApp AI Ordering Bot</h1>
            <p>Hinglish Conversational Commerce • Spring Boot + React + Razorpay UPI + Meta/Twilio Webhooks</p>
          </div>
        </div>

        <div className="status-pills">
          <span className={`pill ${health?.drcLiveSynced ? 'green' : 'amber'}`}>
            <Store size={13} />
            {health?.drcLiveSynced
              ? `DRC Live Sync (${health.catalogCount} SKUs)`
              : `Fallback Catalog (${health?.catalogCount ?? 0} SKUs)`}
          </span>
          <span className="pill blue">
            <Code2 size={13} />
            {health?.llmMode ?? 'Hinglish NLP'}
          </span>
          <span className={`pill ${health?.razorpayLiveConfigured ? 'green' : 'amber'}`}>
            Razorpay: {health?.razorpayLiveConfigured ? 'Live' : 'UPI Demo'}
          </span>
          <button type="button" className="btn" onClick={() => setShowWebhookDocs(true)}>
            <Webhook size={14} /> Webhook Setup
          </button>
        </div>
      </header>

      {errorBanner && (
        <div
          style={{
            margin: '12px 24px 0',
            padding: '10px 14px',
            borderRadius: 10,
            background: 'rgba(244, 63, 94, 0.16)',
            border: '1px solid rgba(244, 63, 94, 0.4)',
            color: '#fecdd3',
            fontSize: 13,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <span>⚠️ {errorBanner}</span>
          <button
            type="button"
            onClick={() => setErrorBanner(null)}
            style={{
              border: 'none',
              background: 'transparent',
              color: '#fecdd3',
              cursor: 'pointer',
              fontWeight: 700,
              fontSize: 14,
            }}
          >
            ×
          </button>
        </div>
      )}

      <main className="workspace-grid">
        <WhatsAppPhoneSimulator
          phone={phone}
          customerName={customerName}
          messages={session?.messages ?? []}
          activeOrder={session?.activeOrder ?? null}
          sending={sending}
          allowSimulatePay={health?.allowSimulatePay ?? true}
          onSend={handleSend}
          onReset={handleReset}
          onSimulatePay={handleSimulatePay}
          onChangeProfile={handleChangeProfile}
        />

        <section className="console-panel">
          <div className="tabs-header">
            <div className="tabs-list">
              <button
                type="button"
                className={`tab-btn ${activeTab === 'inspector' ? 'active' : ''}`}
                onClick={() => setActiveTab('inspector')}
              >
                <Activity size={15} /> AI Pipeline Inspector
              </button>
              <button
                type="button"
                className={`tab-btn ${activeTab === 'merchant' ? 'active' : ''}`}
                onClick={() => setActiveTab('merchant')}
              >
                <PackageCheck size={15} /> Merchant Dispatch ({orders.length})
              </button>
              <button
                type="button"
                className={`tab-btn ${activeTab === 'catalog' ? 'active' : ''}`}
                onClick={() => setActiveTab('catalog')}
              >
                <Store size={15} /> Live DRC Catalog ({catalog.length})
              </button>
            </div>
          </div>

          <div className="console-body">
            {activeTab === 'inspector' && <AiPipelineInspector session={session} />}
            {activeTab === 'merchant' && (
              <MerchantOrderConsole
                orders={orders}
                allowSimulatePay={health?.allowSimulatePay ?? true}
                onSimulatePay={handleSimulatePay}
                onUpdateStatus={handleUpdateOrderStatus}
              />
            )}
            {activeTab === 'catalog' && (
              <CatalogAndSyncPanel
                products={catalog}
                storeRules={storeRules}
                drcApiBaseUrl={drcApiBaseUrl}
                lastLiveSyncOk={lastLiveSyncOk}
                lastSyncMessage={lastSyncMessage}
                onSync={handleSyncCatalog}
                onAddProductToChat={(productName) => void handleSend(`add 1 ${productName}`)}
              />
            )}
          </div>
        </section>
      </main>

      {showWebhookDocs && <WebhookDocsModal onClose={() => setShowWebhookDocs(false)} />}
    </div>
  );
}

