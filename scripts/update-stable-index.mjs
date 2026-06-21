import crypto from "node:crypto";
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

function sha256(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

const args = parseArgs(process.argv.slice(2));
const versionCode = Number.parseInt(args["version-code"] ?? "", 10);
const versionName = args["version-name"];
const tag = args.tag ?? `v${versionName}`;
const repository = args.repository ?? "BigSweetPotatoStudio/hyper-browser";
const signedApkDir = resolveFromRoot(args["signed-apk-dir"] ?? "app/build/outputs/apk/release/signed");
const outputPath = resolveFromRoot(args["output-path"] ?? "update/stable.json");
const minSdk = Number.parseInt(args["min-sdk"] ?? "26", 10);

if (!Number.isInteger(versionCode) || versionCode <= 0) throw new Error("Missing or invalid --version-code");
if (!versionName) throw new Error("Missing --version-name");
if (!Number.isInteger(minSdk) || minSdk <= 0) throw new Error("Missing or invalid --min-sdk");

let notes = args.notes ?? "";
if (args["notes-file"]) {
  const notesPath = resolveFromRoot(args["notes-file"]);
  if (!fs.existsSync(notesPath)) throw new Error(`Notes file does not exist: ${notesPath}`);
  notes = fs.readFileSync(notesPath, "utf8").replace(/^\uFEFF/, "");
}
notes = notes.trim();
if (!notes) throw new Error("Release notes are required. Pass --notes or --notes-file.");

if (!fs.existsSync(signedApkDir)) {
  throw new Error(`Signed APK directory does not exist: ${signedApkDir}`);
}

const abis = ["arm64-v8a", "x86_64"];
const assets = abis.map((abi) => {
  const apkName = `HyperBrowser-${abi}-release.apk`;
  const apkPath = path.join(signedApkDir, apkName);
  if (!fs.existsSync(apkPath)) throw new Error(`Missing signed APK for ${abi}: ${apkPath}`);
  const stat = fs.statSync(apkPath);
  return {
    abi,
    url: `https://github.com/${repository}/releases/download/${tag}/${apkName}`,
    sha256: sha256(apkPath),
    sizeBytes: stat.size,
  };
});

const index = {
  channel: "stable",
  versionCode,
  versionName,
  minSdk,
  notes,
  releaseUrl: `https://github.com/${repository}/releases/tag/${tag}`,
  assets,
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(index, null, 2)}\n`, "utf8");

console.log(`Updated ${path.relative(root, outputPath)} for ${tag} with ${assets.length} APK assets.`);
