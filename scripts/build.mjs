import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";

const output = "backend/dist/server.js";
const binaries = process.platform === "win32"
  ? ["node_modules/.bin/tsc.cmd", "backend/node_modules/.bin/tsc.cmd"]
  : ["node_modules/.bin/tsc", "backend/node_modules/.bin/tsc"];
const compiler = binaries.find(existsSync);

if (compiler) {
  const result = spawnSync(compiler, ["-p", "backend/tsconfig.json"], { stdio: "inherit" });
  if (result.status === 0) process.exit(0);
  if (!existsSync(output)) process.exit(result.status ?? 1);
  console.warn("tsc falló; se utilizará el artefacto compilado incluido en backend/dist.");
} else if (!existsSync(output)) {
  console.error("No hay tsc ni artefacto backend/dist/server.js disponible.");
  process.exit(1);
} else {
  console.warn("tsc no está instalado; se utilizará backend/dist ya compilado.");
}
