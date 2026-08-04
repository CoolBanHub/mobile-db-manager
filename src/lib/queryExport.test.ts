import { unzipSync } from "fflate";
import { describe, expect, it } from "vitest";
import {
  queryExportFilename,
  queryResultToCsv,
  queryResultToJson,
  queryResultToMarkdown,
  queryResultToXlsx,
} from "./queryExport";

describe("queryResultToCsv", () => {
  it("writes an Excel-friendly BOM, header, and CRLF rows", () => {
    const csv = queryResultToCsv({
      columns: ["编号", "name"],
      rows: [[1, "测试"]],
    });

    expect(csv).toBe('\uFEFF"编号","name"\r\n"1","测试"\r\n');
  });

  it("escapes quotes, commas, newlines, objects, and null values", () => {
    const csv = queryResultToCsv({
      columns: ["text", "payload", "missing"],
      rows: [['a,"b"\nnext', { ok: true }, null]],
    });

    expect(csv).toContain('"a,""b""\nnext"');
    expect(csv).toContain('"{""ok"":true}"');
    expect(csv).toContain('"{""ok"":true}",\r\n');
  });

  it("neutralizes spreadsheet formulas without changing ordinary negative numbers", () => {
    const csv = queryResultToCsv({
      columns: ["formula", "number"],
      rows: [["=HYPERLINK(\"https://example.invalid\")", -12]],
    });

    expect(csv).toContain(`"'=HYPERLINK(""https://example.invalid"")"`);
    expect(csv).toContain('"-12"');
  });

  it("exports a header-only result and rejects non-tabular results", () => {
    expect(queryResultToCsv({ columns: ["id"], rows: [] })).toBe('\uFEFF"id"\r\n');
    expect(() => queryResultToCsv({ columns: [], rows: [] })).toThrow("当前结果没有可导出的列");
  });
});

describe("queryExportFilename", () => {
  it("uses safe database and schema segments with a stable UTC timestamp", () => {
    expect(queryExportFilename("sales / 华东", "../reporting", new Date("2026-07-29T01:02:03.456Z"))).toBe(
      "dbx-sales-华东-reporting-2026-07-29T01-02-03Z.csv",
    );
  });

  it("uses the extension for every supported format", () => {
    const now = new Date("2026-07-29T01:02:03.456Z");
    expect(queryExportFilename("db", null, now, "json")).toMatch(/\.json$/);
    expect(queryExportFilename("db", null, now, "markdown")).toMatch(/\.md$/);
    expect(queryExportFilename("db", null, now, "xlsx")).toMatch(/\.xlsx$/);
  });
});

describe("additional query exports", () => {
  const result = {
    columns: ["id", "notes"],
    rows: [[1, "a | b\nnext"]],
  };

  it("keeps JSON columns and rows lossless", () => {
    expect(JSON.parse(queryResultToJson(result))).toEqual(result);
  });

  it("escapes Markdown table separators and newlines", () => {
    expect(queryResultToMarkdown(result)).toContain("a \\| b<br>next");
  });

  it("creates a valid XLSX package with a worksheet", () => {
    const files = unzipSync(queryResultToXlsx(result));
    expect(Object.keys(files)).toContain("xl/worksheets/sheet1.xml");
    expect(new TextDecoder().decode(files["xl/worksheets/sheet1.xml"])).toContain("a | b");
  });
});
