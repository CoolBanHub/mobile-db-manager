export const REVIEW_DIFF_FILTER = "ACDMR";

export function changedFileCommands(base) {
  const commands = [];
  if (base) commands.push(["diff", "--name-only", `--diff-filter=${REVIEW_DIFF_FILTER}`, `${base}...HEAD`]);
  commands.push(
    ["diff", "--name-only", `--diff-filter=${REVIEW_DIFF_FILTER}`],
    ["diff", "--cached", "--name-only", `--diff-filter=${REVIEW_DIFF_FILTER}`],
    ["ls-files", "--others", "--exclude-standard"],
  );
  return commands;
}

export function collectChangedFiles(outputs) {
  const files = new Set();
  for (const output of outputs) {
    for (const line of output.split(/\r?\n/)) {
      const file = line.trim();
      if (file) files.add(file);
    }
  }
  return [...files].sort();
}
