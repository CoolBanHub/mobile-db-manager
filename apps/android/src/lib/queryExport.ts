import { Capacitor } from "@capacitor/core";
import { strToU8, zipSync } from "fflate";
import type { QueryResult } from "./mobileApi";

export type QueryExportFormat = "csv" | "json" | "markdown" | "xlsx";

export interface QueryExportContext {
  result: QueryResult;
  database: string;
  schema?: string | null;
  now?: Date;
}

export interface QueryExportReceipt {
  filename: string;
  format: QueryExportFormat;
  delivery: "share" | "download";
}

interface QueryExportArtifact {
  data: string | Uint8Array;
  mime: string;
}

function spreadsheetSafeText(value: string): string {
  const candidate = value.trimStart();
  const ordinaryNegativeNumber = /^-\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(candidate);
  return /^[=+@]/.test(candidate) || (candidate.startsWith("-") && !ordinaryNegativeNumber) ? `'${value}` : value;
}

function serializedValue(value: unknown): string {
  if (value === null || value === undefined) return "";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

function csvCell(value: unknown): string {
  if (value === null || value === undefined) return "";
  return `"${spreadsheetSafeText(serializedValue(value)).replaceAll('"', '""')}"`;
}

export function queryResultToCsv(result: Pick<QueryResult, "columns" | "rows">): string {
  if (result.columns.length === 0) throw new Error("当前结果没有可导出的列");
  const width = result.columns.length;
  const lines = [
    result.columns.map(csvCell).join(","),
    ...result.rows.map((row) => Array.from({ length: width }, (_, index) => csvCell(row[index])).join(",")),
  ];
  return `\uFEFF${lines.join("\r\n")}\r\n`;
}

export function queryResultToJson(result: Pick<QueryResult, "columns" | "rows">): string {
  if (result.columns.length === 0) throw new Error("当前结果没有可导出的列");
  return `${JSON.stringify({ columns: result.columns, rows: result.rows }, null, 2)}\n`;
}

function markdownCell(value: unknown): string {
  return serializedValue(value).replaceAll("\\", "\\\\").replaceAll("|", "\\|").replace(/\r?\n/g, "<br>");
}

export function queryResultToMarkdown(result: Pick<QueryResult, "columns" | "rows">): string {
  if (result.columns.length === 0) throw new Error("当前结果没有可导出的列");
  const width = result.columns.length;
  return [
    `| ${result.columns.map(markdownCell).join(" | ")} |`,
    `| ${result.columns.map(() => "---").join(" | ")} |`,
    ...result.rows.map(
      (row) => `| ${Array.from({ length: width }, (_, index) => markdownCell(row[index])).join(" | ")} |`,
    ),
    "",
  ].join("\n");
}

function xmlText(value: string): string {
  return value
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "\uFFFD")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function columnName(index: number): string {
  let value = index + 1;
  let name = "";
  while (value > 0) {
    value--;
    name = String.fromCharCode(65 + (value % 26)) + name;
    value = Math.floor(value / 26);
  }
  return name;
}

function xlsxCell(value: unknown, rowIndex: number, columnIndex: number): string {
  const reference = `${columnName(columnIndex)}${rowIndex + 1}`;
  if (value === null || value === undefined) return `<c r="${reference}"/>`;
  if (typeof value === "number" && Number.isFinite(value)) return `<c r="${reference}"><v>${value}</v></c>`;
  if (typeof value === "boolean") return `<c r="${reference}" t="b"><v>${value ? 1 : 0}</v></c>`;
  return `<c r="${reference}" t="inlineStr"><is><t xml:space="preserve">${xmlText(serializedValue(value))}</t></is></c>`;
}

export function queryResultToXlsx(result: Pick<QueryResult, "columns" | "rows">): Uint8Array {
  if (result.columns.length === 0) throw new Error("当前结果没有可导出的列");
  const rows = [result.columns, ...result.rows]
    .map(
      (row, rowIndex) =>
        `<row r="${rowIndex + 1}">${Array.from({ length: result.columns.length }, (_, columnIndex) =>
          xlsxCell(row[columnIndex], rowIndex, columnIndex),
        ).join("")}</row>`,
    )
    .join("");
  const files: Record<string, Uint8Array> = {
    "[Content_Types].xml": strToU8(
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>',
    ),
    "_rels/.rels": strToU8(
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>',
    ),
    "xl/workbook.xml": strToU8(
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Query Result" sheetId="1" r:id="rId1"/></sheets></workbook>',
    ),
    "xl/_rels/workbook.xml.rels": strToU8(
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>',
    ),
    "xl/styles.xml": strToU8(
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Aptos"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf xfId="0"/></cellXfs></styleSheet>',
    ),
    "xl/worksheets/sheet1.xml": strToU8(
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows}</sheetData><autoFilter ref="A1:${columnName(result.columns.length - 1)}${result.rows.length + 1}"/></worksheet>`,
    ),
  };
  return zipSync(files, { level: 6 });
}

function safeFilenameSegment(value: string): string {
  const normalized = value
    .normalize("NFKC")
    .replace(/[^\p{Letter}\p{Number}._-]+/gu, "-")
    .replace(/^[.-]+|[.-]+$/g, "")
    .slice(0, 48);
  return normalized || "default";
}

export function queryExportFilename(
  database: string,
  schema: string | null | undefined,
  now = new Date(),
  format: QueryExportFormat = "csv",
): string {
  const timestamp = now.toISOString().replace(/\.\d{3}Z$/, "Z").replaceAll(":", "-");
  const extension = format === "markdown" ? "md" : format;
  return `dbx-${safeFilenameSegment(database)}-${safeFilenameSegment(schema || "default")}-${timestamp}.${extension}`;
}

function artifactFor(result: Pick<QueryResult, "columns" | "rows">, format: QueryExportFormat): QueryExportArtifact {
  if (format === "json") return { data: queryResultToJson(result), mime: "application/json;charset=utf-8" };
  if (format === "markdown") return { data: queryResultToMarkdown(result), mime: "text/markdown;charset=utf-8" };
  if (format === "xlsx") {
    return {
      data: queryResultToXlsx(result),
      mime: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    };
  }
  return { data: queryResultToCsv(result), mime: "text/csv;charset=utf-8" };
}

function downloadArtifact(filename: string, artifact: QueryExportArtifact) {
  const url = URL.createObjectURL(new Blob([artifact.data as BlobPart], { type: artifact.mime }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  globalThis.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }
  return globalThis.btoa(binary);
}

export async function exportQueryResult(
  context: QueryExportContext,
  format: QueryExportFormat = "csv",
): Promise<QueryExportReceipt> {
  const artifact = artifactFor(context.result, format);
  const filename = queryExportFilename(context.database, context.schema, context.now, format);

  if (!Capacitor.isNativePlatform()) {
    downloadArtifact(filename, artifact);
    return { filename, format, delivery: "download" };
  }

  const [{ Directory, Encoding, Filesystem }, { Share }] = await Promise.all([
    import("@capacitor/filesystem"),
    import("@capacitor/share"),
  ]);
  const written =
    artifact.data instanceof Uint8Array
      ? await Filesystem.writeFile({
          path: filename,
          data: bytesToBase64(artifact.data),
          directory: Directory.Cache,
        })
      : await Filesystem.writeFile({
          path: filename,
          data: artifact.data,
          directory: Directory.Cache,
          encoding: Encoding.UTF8,
        });
  await Share.share({
    title: "DBX 查询结果",
    text: `${context.database}${context.schema ? ` / ${context.schema}` : ""} · ${context.result.rows.length} 行`,
    files: [written.uri],
    dialogTitle: `分享 ${format.toUpperCase()} 查询结果`,
  });
  return { filename, format, delivery: "share" };
}
