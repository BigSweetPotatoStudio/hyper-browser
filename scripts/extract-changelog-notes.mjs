import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (!item.startsWith("--")) throw new Error(`Unexpected argument: ${item}`);
    const key = item.slice(2);
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`Missing value for --${key}`);
    args[key] = value;
    index += 1;
  }
  return args;
}

function resolveFromRoot(filePath) {
  return path.isAbsolute(filePath) ? filePath : path.join(root, filePath);
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function writeUtf8(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${value.trim()}\n`, "utf8");
}

const args = parseArgs(process.argv.slice(2));
const versionName = args.version;
if (!versionName) throw new Error("Missing --version");

const changelogPath = resolveFromRoot(args.changelog ?? "CHANGELOG.md");
const updateNotesPath = resolveFromRoot(args["update-notes-output"] ?? ".release/update-notes.txt");
const releaseNotesPath = resolveFromRoot(args["release-notes-output"] ?? ".release/release-notes.md");

const changelog = fs.readFileSync(changelogPath, "utf8").replace(/^\uFEFF/, "");
const lines = changelog.split(/\r?\n/);
const headingPattern = new RegExp(`^\\s*##\\s+v?${escapeRegExp(versionName)}(?:\\s+-.*)?\\s*$`);

const start = lines.findIndex((line) => headingPattern.test(line));
if (start < 0) throw new Error(`CHANGELOG.md does not contain a section for version ${versionName}.`);

const section = [];
for (let index = start + 1; index < lines.length; index += 1) {
  if (/^\s*##\s+/.test(lines[index])) break;
  section.push(lines[index]);
}

while (section.length > 0 && section[0].trim() === "") section.shift();
while (section.length > 0 && section[section.length - 1].trim() === "") section.pop();
if (section.length === 0) throw new Error(`CHANGELOG.md section for ${versionName} is empty.`);

const markdownNotes = section.join("\n").trim();
const updateNotes = section
  .map((line) => {
    const trimmed = line.trim();
    const bullet = trimmed.match(/^[-*]\s+(.+)$/);
    return bullet ? bullet[1] : trimmed;
  })
  .join("\n")
  .trim();

const releaseNotes = `## Hyper Browser ${versionName}

${markdownNotes}

### APK Selection

- \`HyperBrowser-arm64-v8a-release.apk\`: most modern Android phones.
- \`HyperBrowser-armeabi-v7a-release.apk\`: older 32-bit Android devices.
- \`HyperBrowser-x86_64-release.apk\`: some emulators and x86 Android devices.

### Note

If you previously installed a debug-signed build, you may need to uninstall it before installing this release-signed build.
`;

writeUtf8(updateNotesPath, updateNotes);
writeUtf8(releaseNotesPath, releaseNotes);

console.log(`Extracted changelog notes for ${versionName}.`);
