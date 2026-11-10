# DRC WhatsApp Conversational Commerce & AI Ordering Bot

A standalone **Hinglish AI WhatsApp Grocery Ordering Bot** built for **DRC Krishna Gen Store** using **Java 17 + Spring Boot 3**, **React + TypeScript (Vite)**, **Gemini / OpenAI LLM Entity Extraction** (with deterministic Hinglish NLP fallback), **Fuzzy Catalog Matching**, **Razorpay UPI Payment Links**, and **Meta WhatsApp Cloud API + Twilio Webhooks**.

---

## Core Capabilities

1. **Hinglish LLM Entity Extraction (`LlmHinglishParserService`)**
   - Understands mixed Hindi/English grocery lists such as:
     > *"Bhaiya 2 packet Amul milk, 1 brown bread, aur 5kg Aashirvaad atta deliver kar do"*
   - Extracts intent, normalized items, Hindi numbers (`ek`, `do`, `teen`, `char`, `paanch`), pack sizes (`5kg`, `500g`, `1L`), and inline delivery addresses.
   - Uses **Gemini API** (`gemini-2.5-flash`) or **OpenAI API** (`gpt-4o-mini`) when configured, with an instant zero-key **Hinglish NLP Fallback** so demos work immediately out of the box.

2. **Hybrid Live DRC Catalog Sync + Fuzzy Matcher (`DrcCatalogSyncService`, `FuzzyCatalogMatcherService`)**
   - Syncs live products and delivery rules (`minOrderValue`, `deliveryFee`, `freeDeliveryAbove`) from DRC's REST API (`GET /api/products`, `GET /api/store`), with automatic local DRC fallback if offline.
   - Combines Hinglish synonym expansion (`doodh` → milk, `chawal` → rice, `tel` → oil, `namak` → salt, `chai` → tea), Levenshtein typo tolerance, and pack-size-aware quantity calculation (`5kg Aashirvaad atta` → `1x Aashirvaad Atta 5 kg`).

3. **Razorpay UPI Payment Links & Checkout (`RazorpayPaymentLinkService`, `PaymentCallbackController`)**
   - Computes itemized bills with live delivery fee & free-delivery threshold nudges.
   - Generates live **Razorpay Payment Links** (`POST /api/payment_links`) when API keys are set, or a built-in UPI checkout page (`GET /api/payments/checkout?orderCode=...`) + `upi://pay?...` deep links in local/demo mode.
   - Serves a confirmation landing page at `GET /api/payments/callback?orderCode=...`.
   - Verifies signed webhooks (`POST /api/webhooks/razorpay` via `X-Razorpay-Signature` HMAC-SHA256) for `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`, and `payment.failed`.

4. **Built-in WhatsApp Simulator + Real Meta/Twilio Webhooks**
   - **Web Chat Simulator:** Authentic WhatsApp UI with multi-customer profile switcher + real-time **AI Pipeline Inspector** + **Merchant Dispatch Console** (filter/search, cancel, OTP verification) connected via Server-Sent Events (SSE).
   - **Meta Cloud API:** `GET /api/webhooks/whatsapp` (hub challenge verification) & `POST /api/webhooks/whatsapp` (`X-Hub-Signature-256` verification, text/interactive/location messages, interactive button replies).
   - **Twilio WhatsApp:** `POST /api/webhooks/twilio` (`X-Twilio-Signature` verification + TwiML XML response).
   - **Inventory & Order Lifecycle:** Stock deduction on checkout, automatic stock restoration on customer/merchant cancellation, order transition validation, and delivery OTP verification.
   - **Security Guards:** Optional `ADMIN_API_KEY`, `STRICT_WEBHOOK_VERIFICATION`, and `ALLOW_SIMULATE_PAY` toggles in `.env`.

---

## Quick Start (Local Development)

### 1. Run Backend (Port 8090)
```powershell
cd backend
mvn spring-boot:run
```
*(Spring Boot automatically imports root `.env` when running locally; OS/Docker env vars override `.env`.)*

### 2. Run Frontend (Port 5175)
```powershell
cd frontend
npm install
npm run dev
```

Or run both in one command on Windows:
```powershell
.\start.ps1
```

Open **http://localhost:5175** in your browser.

---

## Production Deployment (Docker)

```bash
cp .env.example .env
# Set PUBLIC_BASE_URL, CORS_ALLOWED_ORIGINS, LLM/WhatsApp/Razorpay keys,
# STRICT_WEBHOOK_VERIFICATION=true, ALLOW_SIMULATE_PAY=false, ADMIN_API_KEY
docker compose up -d --build
```

---

## Verification Tests

```powershell
# Backend unit + integration tests
cd backend
mvn test

# Frontend typecheck, unit tests & production build
cd ..\frontend
npm run typecheck
npm test
npm run build
```

