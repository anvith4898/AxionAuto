# AxionAuto — Features for Instagram Influencers

> Automate your DMs. Focus on creating content.

---

## What Is AxionAuto?

AxionAuto is an Instagram DM automation tool built for creators and influencers who receive high volumes of direct messages every day. Instead of manually replying to hundreds of "link?", "collab?", or "price?" DMs, AxionAuto handles them instantly — 24/7 — so you never miss an opportunity and your audience always feels heard.

---

## Core Features

### 1. 🤖 Automatic DM Replies

Respond to incoming DMs the moment they arrive — even while you're sleeping, filming, or live streaming.

- **Instant replies** triggered by keywords in the message
- **Welcome messages** sent automatically to anyone who DMs you for the first time
- **Fallback replies** for messages that don't match any keyword, so no DM goes unanswered
- Replies are sent from your own Instagram account — nothing looks automated to your audience

**Example use case:**
> A fan DMs you "what camera do you use?" → AxionAuto instantly replies with your gear list and an affiliate link.

---

### 2. 🔑 Keyword-Based Triggers

Set up rules that fire when someone mentions a specific word or phrase in their DM.

- Add as many keywords as you want per rule (e.g., `price`, `pricing`, `cost`, `rates`)
- Keywords are matched regardless of how the word appears in a sentence
- Multiple keywords can trigger the same reply — no need to create duplicate rules
- Case-insensitive matching — `COLLAB`, `Collab`, and `collab` all trigger the same rule

**Common keyword rules influencers set up:**

| Keyword(s) | Auto-Reply Example |
|---|---|
| `collab`, `partnership`, `sponsor` | "Thanks for reaching out! Here's my media kit: [link]" |
| `price`, `rates`, `how much` | "My DM rate starts at $X. Book here: [link]" |
| `preset`, `filter`, `editing` | "Get my Lightroom presets here: [link]" |
| `merch`, `shop`, `buy` | "Check out my store: [link]" |
| `link`, `linktr` | "Here's my Linktree: [link]" |

---

### 3. 👋 First-DM Welcome Message

Automatically send a warm welcome to every **new** follower or fan who DMs you for the first time.

- Fires only once per person — not on every message, just the very first one
- Great for onboarding new followers into your world
- Can include your Linktree, latest drop, or a personal touch

**Example:**
> "Hey! 👋 So happy you reached out! I'm [Your Name]. Here's everything in one place: [Linktree]. Talk soon! ✨"

---

### 4. 🛡️ Smart Cooldown — No Spam, Ever

AxionAuto has a built-in cooldown system so the same person never receives the same automated reply more than once per hour (configurable).

- Prevents your automation from spamming the same follower repeatedly
- Each rule has its own independent cooldown timer
- Set cooldown to `0` for rules where repeating is fine (e.g., always send the merch link)
- Set a longer cooldown (e.g., 24 hours) for welcome messages

---

### 5. 📋 Multiple Rules, Priority Control

Create as many automation rules as you need and control which one fires first.

- **Priority ordering** — Rules with lower priority numbers fire first. Set a VIP collab keyword at priority 1 so it always beats your generic fallback.
- **First Match mode** — Only the most relevant rule fires per message (avoids sending multiple replies)
- **Run All mode** — Multiple rules can all fire for the same message (for advanced setups)
- Enable or disable individual rules instantly without deleting them

---

### 6. 📱 Inbox & Conversation History

View all your Instagram DM threads in one clean, organized inbox inside AxionAuto.

- See your full conversation history with each follower
- Inbound messages (from followers) and outbound replies (sent by automation) in one thread view
- Jump in and reply manually whenever you want — automation and manual replies work side by side
- Threads sorted by most recent activity

---

### 7. 📊 Automation Dashboard

A real-time dashboard showing exactly how your automation is performing.

| Metric | What It Tells You |
|---|---|
| **Messages Received** | Total DMs processed |
| **Automation Triggered** | How many times a rule fired |
| **DMs Sent** | Total automated replies sent this period |
| **Active Accounts** | Number of connected Instagram accounts |

---

### 8. 🔗 Connect Multiple Instagram Accounts

Running multiple Instagram profiles? Connect them all.

- Each Instagram Business Account gets its own set of automation rules
- Use different reply sets for your personal brand vs. your business page
- Accounts are fully isolated from each other — no cross-contamination

---

### 9. ✏️ Personalized Reply Templates

Make automated replies feel personal — not robotic.

Use placeholder variables inside your reply text to personalize each message:

| Placeholder | What It Inserts |
|---|---|
| `{sender_id}` | The fan's Instagram user ID |

Combine with your own warm language and emojis to make every auto-reply feel human.

---

### 10. 🔒 Secure Instagram Connection (OAuth)

Connecting your Instagram account is secure and takes 30 seconds.

- Uses Meta's official **OAuth 2.0** authorization — you never share your password
- AxionAuto is granted only the specific permissions needed (`instagram_manage_messages`)
- Your access token is stored **AES-256-GCM encrypted** — even AxionAuto staff cannot read it
- Tokens automatically refresh every 60 days — no expiry surprises

---

## Who Is This For?

AxionAuto is built for:

| Creator Type | Pain Point Solved |
|---|---|
| **UGC Creators** | Instantly reply to brand inquiries with your rate card |
| **Lifestyle Influencers** | Auto-send your Linktree or preset shop to every "link?" DM |
| **Fitness Coaches** | Auto-reply to "program?", "diet plan?" with your booking link |
| **Fashion / Beauty** | Auto-reply to "where is this from?" with affiliate links |
| **Musicians / Artists** | Welcome new followers, auto-send Spotify/merch links |
| **Podcasters** | Auto-reply to episode requests or guest pitches |

---

## What AxionAuto Does NOT Do

To keep your account safe and compliant with Instagram's policies:

- ❌ Does **not** send unsolicited DMs (only replies to messages sent to you)
- ❌ Does **not** mass-DM your followers or cold-outreach
- ❌ Does **not** require your Instagram password
- ❌ Does **not** engage with stories, comments, or posts (DMs only)
- ❌ Does **not** exceed Instagram's messaging rate limits

---

## Getting Started

1. **Sign up** at [your domain]
2. **Connect your Instagram Business Account** — takes under 30 seconds via OAuth
3. **Create your first rule** — keyword, reply text, cooldown
4. **Go live** — toggle automation on and start replying automatically

> 💡 **Pro tip:** Start with a WELCOME rule and a `price` / `collab` keyword rule. Those two alone handle 80% of influencer DMs.
