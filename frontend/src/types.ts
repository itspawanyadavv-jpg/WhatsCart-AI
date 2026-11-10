export type ConversationState =
  | 'IDLE'
  | 'REVIEWING_CART'
  | 'AWAITING_ADDRESS'
  | 'AWAITING_PAYMENT';

export type BotOrderStatus =
  | 'PLACED'
  | 'CONFIRMED'
  | 'PACKING'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLED';

export type BotPaymentStatus = 'PENDING' | 'PAID' | 'COD';

export interface ParsedItem {
  rawText: string;
  productQuery: string;
  quantity: number;
  unit: string;
  packSizeMention: string | null;
}

export interface LlmParseResult {
  intent: string;
  items: ParsedItem[];
  deliveryAddress: string | null;
  parserUsed: string;
  latencyMs: number;
  normalizedSummary: string;
}

export interface CatalogMatchResult {
  rawQuery: string;
  requestedQty: number;
  matched: boolean;
  outOfStock: boolean;
  productId: number | null;
  matchedProductName: string | null;
  unitPrice: number | null;
  unit: string | null;
  availableStock: number;
  confidence: number;
  matchReason: string;
  alternatives: string[];
}

export interface CartItemEntry {
  productId: number;
  productName: string;
  unit: string;
  unitPrice: number;
  quantity: number;
  rawQuery: string;
  confidence: number;
}

export interface StoreRulesDto {
  deliveryRadiusKm: number;
  minOrderValue: number;
  deliveryFee: number;
  freeDeliveryAbove: number;
  liveSynced: boolean;
}

export interface ChatMessage {
  id: number;
  phone: string;
  direction: 'INBOUND' | 'OUTBOUND';
  channel: string;
  body: string;
  metadataJson: string | null;
  createdAt: string;
}

export interface BotOrderItem {
  id: number;
  productId: number;
  productName: string;
  unit: string;
  rawQuery: string;
  matchConfidence: number;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface BotOrder {
  id: number;
  orderCode: string;
  phone: string;
  customerName: string;
  deliveryAddress: string;
  status: BotOrderStatus;
  paymentMethod: string;
  paymentStatus: BotPaymentStatus;
  subtotal: number;
  deliveryFee: number;
  total: number;
  razorpayPaymentLinkId: string | null;
  razorpayPaymentLinkUrl: string | null;
  razorpayPaymentId: string | null;
  upiDeepLink: string | null;
  channel: string;
  deliveryOtp: string;
  items: BotOrderItem[];
  createdAt: string;
  updatedAt: string;
}

export interface ChatTurnResponse {
  phone: string;
  customerName: string;
  state: ConversationState;
  deliveryAddress: string | null;
  cart: CartItemEntry[];
  subtotal: number;
  deliveryFee: number;
  total: number;
  amountForFreeDelivery: number;
  storeRules: StoreRulesDto;
  parseResult: LlmParseResult | null;
  matchResults: CatalogMatchResult[];
  messages: ChatMessage[];
  activeOrder: BotOrder | null;
}

export interface CatalogProduct {
  id: number;
  externalDrcId: number | null;
  name: string;
  description: string;
  price: number;
  stock: number;
  unit: string;
  categoryName: string;
  imageUrl: string | null;
  source: string;
  synonyms: string;
  syncedAt: string;
}

export interface HealthInfo {
  status: string;
  storeName: string;
  drcApiBaseUrl: string;
  drcLiveSynced: boolean;
  catalogCount: number;
  llmMode: string;
  whatsappMetaConfigured: boolean;
  whatsappTwilioConfigured: boolean;
  razorpayLiveConfigured: boolean;
  allowSimulatePay?: boolean;
  strictWebhookVerification?: boolean;
}

