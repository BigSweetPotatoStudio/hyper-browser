#!/usr/bin/env zx
import { existsSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { $, cd, chalk, usePowerShell } from "zx";

type ScriptOptions = {
  skipAndroidBuild: boolean;
  skipAndroidInstall: boolean;
  skipExtensionInstall: boolean;
  skipExtensionTypecheck: boolean;
  skipExtensionZip: boolean;
  launchAfterInstall: boolean;
};

const repoRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const apkOutputDir = join(repoRoot, "app", "build", "outputs", "apk", "debug");
const packageName = "com.dadigua.hyperbrowser";

$.verbose = true;
if (process.platform === "win32") {
  usePowerShell();
}

function parseOptions(argv: string[]): ScriptOptions {
  const flags = new Set(argv.map((arg) => normalizeFlag(arg)));
  return {
    skipAndroidBuild: flags.has("skipandroidbuild") || flags.has("skip-android-build"),
    skipAndroidInstall: flags.has("skipandroidinstall") || flags.has("skip-android-install"),
    skipExtensionInstall: flags.has("skipextensioninstall") || flags.has("skip-extension-install"),
    skipExtensionTypecheck: flags.has("skipextensiontypecheck") || flags.has("skip-extension-typecheck"),
    skipExtensionZip: flags.has("skipextensionzip") || flags.has("skip-extension-zip"),
    launchAfterInstall: flags.has("launchafterinstall") || flags.has("launch-after-install"),
  };
}

function normalizeFlag(value: string): string {
  return value.replace(/^-+/, "").toLowerCase();
}

async function step(title: string, action: () => Promise<void>): Promise<void> {
  console.log("");
  console.log(chalk.cyan(`==> ${title}`));
  await action();
}

async function requireCommand(name: string): Promise<void> {
  const checker = process.platform === "win32" ? "where.exe" : "which";
  try {
    await $({ quiet: true })`${checker} ${name}`;
  } catch {
    throw new Error(`Required command '${name}' was not found on PATH.`);
  }
}

async function getAdbDevices(): Promise<string[]> {
  const output = await $({ quiet: true })`adb devices`;
  return output.stdout
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.match(/^(\S+)\s+device$/)?.[1])
    .filter((device): device is string => Boolean(device));
}

async function getApkForDevice(device: string): Promise<string> {
  const abiOutput = await $({ quiet: true })`adb -s ${device} shell getprop ro.product.cpu.abi`;
  const abi = abiOutput.stdout.trim();
  const apk = abi === "x86_64"
    ? join(apkOutputDir, "app-x86_64-debug.apk")
    : abi === "arm64-v8a"
      ? join(apkOutputDir, "app-arm64-v8a-debug.apk")
      : "";

  if (!apk) {
    throw new Error(`Unsupported ABI '${abi}' for device '${device}'. Expected arm64-v8a or x86_64.`);
  }
  if (!existsSync(apk)) {
    throw new Error(`APK not found for device '${device}': ${apk}`);
  }
  return apk;
}

async function buildAndroidDebug(): Promise<void> {
  const gradle = process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew";
  if (process.platform === "win32") {
    await $`& ${gradle} :app:assembleDebug --console=plain`;
    return;
  }
  await $`${gradle} :app:assembleDebug --console=plain`;
}

async function installAndroidOnAllDevices(launchAfterInstall: boolean): Promise<void> {
  const devices = await getAdbDevices();
  if (devices.length === 0) {
    throw new Error("No online ADB devices found. Connect a device, enable USB debugging, then retry.");
  }

  for (const device of devices) {
    const apk = await getApkForDevice(device);
    console.log(`Installing ${apk} on ${device}`);
    await $`adb -s ${device} install -r ${apk}`;

    if (launchAfterInstall) {
      console.log(`Launching ${packageName} on ${device}`);
      await $`adb -s ${device} shell monkey -p ${packageName} 1`;
    }
  }
}

async function packageCompanionExtension(): Promise<void> {
  await $`pnpm --dir companion-extension zip`;

  const outputDir = join(repoRoot, "companion-extension", ".output");
  const zips = existsSync(outputDir)
    ? readdirSync(outputDir)
      .filter((name) => name.toLowerCase().endsWith(".zip"))
      .sort()
      .map((name) => join(outputDir, name))
    : [];

  if (zips.length === 0) {
    throw new Error("Companion extension zip finished but no zip files were found in companion-extension\\.output.");
  }

  console.log("");
  console.log("Extension packages:");
  for (const zip of zips) {
    console.log(`  ${zip}`);
  }
}

async function main(): Promise<void> {
  const options = parseOptions(process.argv.slice(2));
  const needsPnpm = !options.skipAndroidBuild ||
    !options.skipExtensionInstall ||
    !options.skipExtensionTypecheck ||
    !options.skipExtensionZip;

  if (needsPnpm) {
    await requireCommand("pnpm");
  }
  if (!options.skipAndroidInstall) {
    await requireCommand("adb");
  }

  if (!options.skipAndroidBuild) {
    await step("Build Android debug APKs", buildAndroidDebug);
  }

  if (!options.skipAndroidInstall) {
    await step("Install Android APK on every connected ADB device", async () => {
      await installAndroidOnAllDevices(options.launchAfterInstall);
    });
  }

  if (!options.skipExtensionInstall) {
    await step("Install companion extension dependencies", async () => {
      await $`pnpm --dir companion-extension install --frozen-lockfile`;
    });
  }

  if (!options.skipExtensionTypecheck) {
    await step("Typecheck companion extension", async () => {
      await $`pnpm --dir companion-extension typecheck`;
    });
  }

  if (!options.skipExtensionZip) {
    await step("Package companion extension for Chrome and Firefox", packageCompanionExtension);
  }
}

try {
  cd(repoRoot);
  await main();
} catch (error) {
  console.error("");
  console.error(chalk.red(error instanceof Error ? error.message : String(error)));
  process.exit(1);
}
