const API = "/api";
const fmtMoney = (n) => Number(n).toLocaleString("en-US", { style: "currency", currency: "USD" });
const fmtTime = (iso) => new Date(iso).toLocaleString();

let selectedAccountId = null;

// ---------- API helper ----------

async function api(path, options = {}) {
  const res = await fetch(API + path, {
    headers: { "Content-Type": "application/json" },
    ...options,
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

// ---------- Accounts ----------

async function refreshAccounts() {
  const accounts = await api("/accounts");
  renderAccountsTable(accounts);
  renderAccountSelects(accounts);
}

function renderAccountsTable(accounts) {
  const tbody = document.querySelector("#accounts-table tbody");
  const empty = document.getElementById("accounts-empty");
  tbody.innerHTML = "";
  empty.classList.toggle("hidden", accounts.length > 0);

  for (const acc of accounts) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${acc.id}</td>
      <td>${escapeHtml(acc.ownerName)}</td>
      <td class="num">${fmtMoney(acc.balance)}</td>
      <td>${fmtTime(acc.createdAt)}</td>
      <td><button class="secondary small" data-history="${acc.id}" data-owner="${escapeHtml(acc.ownerName)}">History</button></td>`;
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
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${fmtTime(t.timestamp)}</td>
      <td class="type-${t.type}">${t.type.replace("_", " ")}</td>
      <td class="num">${fmtMoney(t.amount)}</td>
      <td class="num">${fmtMoney(t.balanceAfter)}</td>
      <td>${t.counterpartyAccountId ? "#" + t.counterpartyAccountId : "—"}</td>`;
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

document.getElementById("transfer-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fromAccountId = Number(document.getElementById("transfer-from").value);
  const toAccountId = Number(document.getElementById("transfer-to").value);
  const amount = Number(document.getElementById("transfer-amount").value);
  try {
    const result = await api("/transfers", {
      method: "POST",
      body: JSON.stringify({ fromAccountId, toAccountId, amount }),
    });
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
