import { describe, expect, it } from "vitest";
import { parseSqlParameterJson, resolveSqlParameters } from "./sqlParameters";

describe("mobile SQL parameters", () => {
  it("parses scalar JSON values and substitutes common placeholders", () => {
    const params = parseSqlParameterJson(JSON.stringify({ name: "O'Reilly", limit: 10, enabled: true, empty: null }));
    expect(resolveSqlParameters("select :name, ${limit}, {{enabled}}, :empty", params)).toBe(
      "select 'O''Reilly', 10, TRUE, NULL",
    );
  });

  it("does not substitute quoted text, comments, or postgres casts", () => {
    expect(resolveSqlParameters("select ':id', value::text -- :id\n/* :id */ where id=:id", { id: 7 })).toBe(
      "select ':id', value::text -- :id\n/* :id */ where id=7",
    );
  });

  it("rejects unsupported values and missing parameters", () => {
    expect(() => parseSqlParameterJson('{"items":[1]}')).toThrow("只支持");
    expect(() => resolveSqlParameters("select :missing", {})).toThrow("缺少 SQL 参数");
  });
});
