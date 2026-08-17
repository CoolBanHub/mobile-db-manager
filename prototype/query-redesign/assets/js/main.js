const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const editor = $("#sqlEditor");
const suggestions = $("#suggestions");
const toast = $("#toast");
const backdrop = $("#backdrop");

function showToast(text) {
  toast.textContent = text;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 1400);
}
function openSheet(sheet) { backdrop.classList.add("open"); sheet.classList.add("open"); }
function closeSheets() { backdrop.classList.remove("open"); $$(".sheet").forEach((sheet) => sheet.classList.remove("open")); }

$("#runMenuButton").addEventListener("click", () => $("#runMenu").classList.toggle("open"));
$("#formatButton").addEventListener("click", () => { editor.value = "SELECT\n  o.id,\n  o.customer_name,\n  o.total_amount\nFROM orders AS o\nWHERE o.status = :status\nORDER BY o.total_amount DESC\nLIMIT 20;"; suggestions.style.display = "none"; showToast("SQL 已格式化"); });
$("#runButton").addEventListener("click", () => {
  $("#runButton b").textContent = "运行中"; $("#stopButton").disabled = false;
  setTimeout(() => { $("#runButton b").textContent = "运行"; $("#stopButton").disabled = true; showToast("查询成功 · 36 ms"); }, 700);
});
$("#stopButton").addEventListener("click", () => { $("#runButton b").textContent = "运行"; $("#stopButton").disabled = true; showToast("已取消查询"); });
$("#explainButton").addEventListener("click", () => { $("[data-panel='plan']").click(); showToast("执行计划已生成"); });
$("#parameterButton").addEventListener("click", () => openSheet($("#parameterSheet")));
$("#connectionButton").addEventListener("click", () => openSheet($("#connectionSheet")));
backdrop.addEventListener("click", closeSheets); $$(".close-sheet").forEach((button) => button.addEventListener("click", closeSheets));

editor.addEventListener("input", () => { suggestions.style.display = /\b(?:from|join)\s+[a-z_]*$/i.test(editor.value) ? "block" : "none"; });
editor.addEventListener("keydown", (event) => { if (event.key === "Tab" && suggestions.style.display !== "none") { event.preventDefault(); const token = editor.value.match(/[a-z_]*$/i)?.[0] || ""; editor.value = editor.value.slice(0, -token.length) + "orders"; suggestions.style.display = "none"; } });
$$(".suggestions button").forEach((button) => button.addEventListener("click", () => { const token = editor.value.match(/[a-z_]*$/i)?.[0] || ""; editor.value = editor.value.slice(0, -token.length) + button.dataset.value; suggestions.style.display = "none"; editor.focus(); }));

$$(".result-tabs button").forEach((button) => button.addEventListener("click", () => {
  $$(".result-tabs button").forEach((item) => item.classList.remove("active")); button.classList.add("active");
  $$(".panel").forEach((panel) => panel.classList.remove("active")); $(`#${button.dataset.panel}Panel`).classList.add("active");
}));
$$(".query-tabs button:not(.add)").forEach((button) => button.addEventListener("click", () => { $$(".query-tabs button").forEach((item) => item.classList.remove("active")); button.classList.add("active"); }));
