import fs from "node:fs/promises";
import { IncomingMessage, ServerResponse } from "node:http";
import { Socket } from "node:net";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useAutoCleanupTempDirTracker } from "../../test/helpers/temp-dir.js";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import * as boundaryFileRead from "../infra/boundary-file-read.js";
import * as devInstallBranch from "../infra/dev-install-branch.js";
import { createEmptyPluginRegistry } from "../plugins/registry-empty.js";
import { resetPluginRuntimeStateForTest, setActivePluginRegistry } from "../plugins/runtime.js";
import { createDeferredCore } from "../shared/deferred.js";
import type { ResolvedGatewayAuth } from "./auth.js";
import { CONTROL_UI_BOOTSTRAP_CONFIG_PATH } from "./control-ui-contract.js";
import { handleControlUiAvatarRequest, handleControlUiHttpRequest } from "./control-ui.js";

const REAL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);
const testTempDirs = useAutoCleanupTempDirTracker(afterEach);
afterEach(() => {
  vi.restoreAllMocks();
  resetPluginRuntimeStateForTest();
});

function createAvatarConfig(workspace: string, avatar: string): OpenClawConfig {
  return {
    agents: {
      defaults: { workspace },
      list: [{ id: "main", workspace, identity: { avatar } }],
    },
  };
}

describe("Control UI response authority", () => {
  it.each(["avatar", "thumbnail", "bootstrap"] as const)(
    "withholds a prepared %s response after requester authority changes",
    async (kind) => {
      const workspace = testTempDirs.make("openclaw-ui-response-authority-");
      await fs.writeFile(path.join(workspace, "avatar.png"), REAL_PNG);
      let config = createAvatarConfig(workspace, "avatar.png");
      const entered = createDeferredCore();
      const release = createDeferredCore();
      if (kind === "bootstrap") {
        const registry = createEmptyPluginRegistry();
        registry.controlUiDescriptors.push({
          pluginId: "demo-plugin",
          source: "demo-plugin",
          descriptor: {
            surface: "tab",
            id: "demo",
            label: "Demo",
            path: "/secure-hook/panel",
          },
        });
        registry.httpRoutes.push({
          pluginId: "demo-plugin",
          source: "demo-plugin",
          path: "/secure-hook",
          auth: "gateway",
          match: "prefix",
          handler: async () => true,
        });
        setActivePluginRegistry(registry);
        vi.spyOn(devInstallBranch, "resolveDevInstallGitBranch").mockImplementation(async () => {
          entered.resolve();
          await release.promise;
          return "private-branch";
        });
      } else {
        const read = boundaryFileRead.readFileDescriptorBounded;
        vi.spyOn(boundaryFileRead, "readFileDescriptorBounded").mockImplementation(
          async (...args) => {
            const body = await read(...args);
            entered.resolve();
            await release.promise;
            return body;
          },
        );
      }
      let auth: ResolvedGatewayAuth = {
        mode: "token",
        token: "admitted-token",
        allowTailscale: false,
      };
      const req = new IncomingMessage(new Socket());
      Object.defineProperty(req.socket, "remoteAddress", { value: "127.0.0.1" });
      req.method = "GET";
      req.url =
        kind === "bootstrap"
          ? CONTROL_UI_BOOTSTRAP_CONFIG_PATH
          : `/avatar/main${kind === "thumbnail" ? "?v=current" : ""}`;
      req.headers = { authorization: "Bearer admitted-token" };
      const res = new ServerResponse(req);
      const end = vi.spyOn(res, "end");
      const setHeader = vi.spyOn(res, "setHeader");
      const handler =
        kind === "bootstrap" ? handleControlUiHttpRequest : handleControlUiAvatarRequest;
      const pending = handler(req, res, {
        config,
        cfg: config,
        auth,
        getResolvedAuth: () => auth,
        getRuntimeConfig: () => config,
      });
      const settled = pending.catch(() => undefined);
      await entered.promise;
      try {
        if (kind === "bootstrap") {
          expect(res.getHeader("Set-Cookie")).toEqual(
            expect.arrayContaining([expect.stringContaining("Path=/secure-hook")]),
          );
          config = { ...config, gateway: { roles: { definitions: {} } } };
        } else {
          auth = { ...auth, token: "replacement-token" };
        }
      } finally {
        release.resolve();
      }
      await settled;

      expect(res.statusCode).toBe(401);
      expect(String(end.mock.calls[0]?.[0])).not.toContain("private-branch");
      expect(end).not.toHaveBeenCalledWith(REAL_PNG);
      expect(setHeader).not.toHaveBeenCalledWith("Content-Type", "image/png");
      expect(setHeader).not.toHaveBeenCalledWith("ETag", expect.anything());
      expect(res.getHeader("Set-Cookie")).toBeUndefined();
    },
  );
});
