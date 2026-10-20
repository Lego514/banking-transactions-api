const API = "/api";
const fmtMoney = (n) => Number(n).toLocaleString("en-US", { style: "currency", currency: "USD" });
const fmtTime = (iso) => new Date(iso).toLocaleString();

const INFLOW = new Set(["DEPOSIT", "TRANSFER_IN"]);
const AVATAR_HUES = [222, 262, 190, 152, 12, 322];

let selectedAccountId = null;

// ---------- API helper ----------

async function api(path, options = {}) {
  const res = await fetch(API + path, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });
  const body = res.status === 204 ? null : await res.json();
  if (!res.ok) {
    throw new Error(body && body.message ? body.message : `Request failed (${res.status})`);
  }
  return body;
}

// ---------- Toast ----------

let toastTimer;
function toast(message, kind) {
  const el = document.getElementById("toast");
  el.textContent = message;
  el.className = `toast ${kind}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add("hidden"), 3500);
}

// ---------- Accounts + KPIs ----------

async function refreshAccounts() {
  const accounts = await api("/accounts");
  renderKpis(accounts);
  renderAccountsTable(accounts);
  renderAccountSelects(accounts);
}

function renderKpis(accounts) {
  const total = accounts.reduce((sum, a) => sum + Number(a.balance), 0);
  document.getElementById("kpi-total").textContent = fmtMoney(total);
  document.getElementById("kpi-accounts").textContent = accounts.length;
  document.getElementById("kpi-avg").textContent =
    fmtMoney(accounts.length ? total / accounts.length : 0);
}

function avatar(account) {
  const initials = account.ownerName
    .split(/\s+/)
    .map((w) => w[0])
    .slice(0, 2)
    .join("")
    .toUpperCase();
  const hue = AVATAR_HUES[account.id % AVATAR_HUES.length];
  return `<span class="avatar" style="background:linear-gradient(135deg,hsl(${hue},70%,55%),hsl(${hue + 40},70%,45%))">${initials}</span>`;
}

function renderAccountsTable(accounts) {
  const tbody = document.querySelector("#accounts-table tbody");
  const empty = document.getElementById("accounts-empty");
  tbody.innerHTML = "";
  empty.classList.toggle("hidden", accounts.length > 0);

  for (const acc of accounts) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td class="acct-id">#${acc.id}</td>
      <td><span class="owner-cell">${avatar(acc)}${escapeHtml(acc.ownerName)}</span></td>
      <td class="num balance-cell">${fmtMoney(acc.balance)}</td>
      <td class="muted">${fmtTime(acc.createdAt)}</td>
      <td><button class="ghost small" data-history="${acc.id}" data-owner="${escapeHtml(acc.ownerName)}">History</button></td>`;
    tbody.appendChild(tr);
  }
}

function renderAccountSelects(accounts) {
  for (const id of ["cash-account", "transfer-from", "transfer-to"]) {
    const select = document.getElementById(id);
    const previous = select.value;
    select.innerHTML = accounts
      .map((a) => `<option value="${a.id}">#${a.id} — ${escapeHtml(a.ownerName)} (${fmtMoney(a.balance)})</option>`)
      .join("");
    if (previous && [...select.options].some((o) => o.value === previous)) select.value = previous;
  }
}

// ---------- Transaction history ----------

async function showHistory(accountId, ownerName) {
  selectedAccountId = accountId;
  document.getElementById("history-owner").textContent = `— #${accountId} ${ownerName}`;
  const txns = await api(`/accounts/${accountId}/transactions`);
  const tbody = document.querySelector("#history-table tbody");
  const empty = document.getElementById("history-empty");
  tbody.innerHTML = "";
  empty.classList.toggle("hidden", txns.length > 0);
  if (txns.length === 0) empty.textContent = "No transactions on this account yet.";

  for (const t of txns) {
    const inflow = INFLOW.has(t.type);
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td class="muted">${fmtTime(t.timestamp)}</td>
      <td><span class="pill ${inflow ? "pill-in" : "pill-out"}">${inflow ? "↓" : "↑"} ${t.type.replace("_", " ")}</span></td>
      <td class="num ${inflow ? "amt-in" : "amt-out"}">${inflow ? "+" : "−"}${fmtMoney(t.amount)}</td>
      <td class="num balance-cell">${fmtMoney(t.balanceAfter)}</td>
      <td>${t.counterpartyAccountId ? `<span class="chip">#${t.counterpartyAccountId}</span>` : "—"}</td>`;
    tbody.appendChild(tr);
  }
}

async function refreshAll() {
  await refreshAccounts();
  if (selectedAccountId !== null) {
    const owner = document.getElementById("history-owner").textContent.replace(/^— #\d+ /, "");
    await showHistory(selectedAccountId, owner).catch(() => {});
  }
}

// ---------- Event wiring ----------

document.getElementById("create-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const ownerName = document.getElementById("owner-name").value.trim();
  const raw = document.getElementById("initial-deposit").value;
  try {
    const acc = await api("/accounts", {
      method: "POST",
      body: JSON.stringify({ ownerName, initialDeposit: raw === "" ? null : Number(raw) }),
    });
    toast(`Account #${acc.id} created for ${acc.ownerName}`, "success");
    e.target.reset();
    await refreshAll();
  } catch (err) {
    toast(err.message, "error");
  }
});

document.getElementById("cash-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const action = e.submitter.dataset.action;
  const id = document.getElementById("cash-account").value;
  const amount = Number(document.getElementById("cash-amount").value);
  try {
    const acc = await api(`/accounts/${id}/${action}`, {
      method: "POST",
      body: JSON.stringify({ amount }),
    });
    toast(`${action === "deposit" ? "Deposited" : "Withdrew"} ${fmtMoney(amount)} — balance ${fmtMoney(acc.balance)}`, "success");
    document.getElementById("cash-amount").value = "";
    await refreshAll();
  } catch (err) {
    toast(err.message, "error");
  }
});

// One key per transfer the user means to make. A double-click, or a resubmit
// after a network error, sends the same key, so the server moves the money once.
// A new key is drawn only after a transfer succeeds.
let transferKey = crypto.randomUUID();

document.getElementById("transfer-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fromAccountId = Number(document.getElementById("transfer-from").value);
  const toAccountId = Number(document.getElementById("transfer-to").value);
  const amount = Number(document.getElementById("transfer-amount").value);
  try {
    const result = await api("/transfers", {
      method: "POST",
      headers: { "Idempotency-Key": transferKey },
      body: JSON.stringify({ fromAccountId, toAccountId, amount }),
    });
    transferKey = crypto.randomUUID();
    toast(`Transferred ${fmtMoney(amount)} from #${result.from.id} to #${result.to.id}`, "success");
    document.getElementById("transfer-amount").value = "";
    await refreshAll();
  } catch (err) {
    toast(err.message, "error");
  }
});

document.querySelector("#accounts-table tbody").addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-history]");
  if (btn) showHistory(Number(btn.dataset.history), btn.dataset.owner).catch((err) => toast(err.message, "error"));
});

document.getElementById("refresh-btn").addEventListener("click", () => refreshAll().catch((err) => toast(err.message, "error")));

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

refreshAccounts().catch((err) => toast(err.message, "error"));
