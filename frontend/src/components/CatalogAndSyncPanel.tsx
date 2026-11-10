import React, { useMemo, useState } from 'react';
import { Plus, RefreshCw, Search, Store } from 'lucide-react';
import type { CatalogProduct, StoreRulesDto } from '../types';

interface Props {
  products: CatalogProduct[];
  storeRules: StoreRulesDto | null;
  drcApiBaseUrl: string;
  lastLiveSyncOk: boolean;
  lastSyncMessage?: string | null;
  onSync: (includeLocalStaples: boolean) => Promise<void>;
  onAddProductToChat: (productName: string) => void;
}

export const CatalogAndSyncPanel: React.FC<Props> = ({
  products,
  storeRules,
  drcApiBaseUrl,
  lastLiveSyncOk,
  lastSyncMessage,
  onSync,
  onAddProductToChat,
}) => {
  const [search, setSearch] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('ALL');
  const [includeLocalStaples, setIncludeLocalStaples] = useState(true);
  const [syncing, setSyncing] = useState(false);

  const categories = useMemo(() => {
    const set = new Set<string>();
    for (const p of products) {
      if (p.categoryName) set.add(p.categoryName);
    }
    return ['ALL', ...Array.from(set).sort()];
  }, [products]);

  const handleSync = async () => {
    setSyncing(true);
    try {
      await onSync(includeLocalStaples);
    } finally {
      setSyncing(false);
    }
  };

  const filtered = products.filter((p) => {
    if (selectedCategory !== 'ALL' && p.categoryName !== selectedCategory) {
      return false;
    }
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return (
      p.name.toLowerCase().includes(q) ||
      (p.synonyms || '').toLowerCase().includes(q) ||
      (p.categoryName || '').toLowerCase().includes(q)
    );
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <div>
            <h3 className="card-title" style={{ marginBottom: 4 }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <Store size={15} color="#00a884" /> DRC Live Store Catalog Sync
              </span>
            </h3>
            <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
              Source: <span className="mono">{drcApiBaseUrl}</span> •{' '}
              <span style={{ color: lastLiveSyncOk ? '#6ee7b7' : '#fcd34d' }}>
                {lastLiveSyncOk ? 'Connected to live DRC API' : 'Local DRC Fallback'}
              </span>
            </div>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={includeLocalStaples}
                onChange={(e) => setIncludeLocalStaples(e.target.checked)}
              />
              Include local Milk/Bread staples
            </label>
            <button type="button" className="btn btn-primary" disabled={syncing} onClick={handleSync}>
              <RefreshCw size={13} /> {syncing ? 'Syncing...' : 'Sync Now'}
            </button>
          </div>
        </div>

        {storeRules && (
          <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
            <span className="badge badge-blue">Radius: {storeRules.deliveryRadiusKm} km</span>
            <span className="badge badge-amber">Min Order: ₹{storeRules.minOrderValue}</span>
            <span className="badge badge-amber">Delivery Fee: ₹{storeRules.deliveryFee}</span>
            <span className="badge badge-green">Free Delivery Above: ₹{storeRules.freeDeliveryAbove}</span>
          </div>
        )}
        {lastSyncMessage && (
          <div style={{ marginTop: 8, fontSize: 12, color: '#6ee7b7' }}>
            ✓ {lastSyncMessage}
          </div>
        )}
      </div>

      <div className="card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <Search size={15} color="#8696a0" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter by product or Hinglish synonym (e.g. doodh, tel, atta, namak)..."
            style={{
              flex: 1,
              background: '#0b141a',
              border: '1px solid var(--border-subtle)',
              borderRadius: 9,
              color: 'white',
              padding: '8px 12px',
              fontSize: 12.5,
              outline: 'none',
            }}
          />
          <span className="mono" style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            {filtered.length} SKUs
          </span>
        </div>

        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
          {categories.map((cat) => (
            <button
              key={cat}
              type="button"
              className={`btn ${selectedCategory === cat ? 'btn-primary' : ''}`}
              style={{ padding: '4px 10px', fontSize: 11 }}
              onClick={() => setSelectedCategory(cat)}
            >
              {cat}
            </button>
          ))}
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))', gap: 10 }}>
          {filtered.map((p) => (
            <div
              key={p.id}
              style={{
                padding: '10px 12px',
                borderRadius: 10,
                background: 'rgba(11, 20, 26, 0.5)',
                border: '1px solid var(--border-subtle)',
                display: 'flex',
                flexDirection: 'column',
                gap: 5,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 6 }}>
                <strong style={{ fontSize: 13 }}>{p.name}</strong>
                <span className="mono" style={{ fontWeight: 700, fontSize: 13, color: '#5eead4' }}>
                  ₹{Number(p.price).toFixed(0)}
                </span>
              </div>

              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <span className={`badge ${p.source === 'LIVE_DRC' ? 'badge-green' : 'badge-blue'}`}>
                  {p.source === 'LIVE_DRC' ? 'LIVE DRC' : p.source}
                </span>
                <span className={`badge ${p.stock > 0 ? 'badge-amber' : 'badge-red'}`}>
                  Stock: {p.stock} {p.unit}
                </span>
              </div>

              {p.synonyms && (
                <div style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
                  Hinglish tags: {p.synonyms}
                </div>
              )}

              <button
                type="button"
                className="btn"
                disabled={p.stock <= 0}
                onClick={() => onAddProductToChat(p.name)}
                style={{ marginTop: 'auto', padding: '5px 8px', fontSize: 11.5 }}
              >
                <Plus size={12} /> {p.stock > 0 ? 'Add 1 to Chat' : 'Out of Stock'}
              </button>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

