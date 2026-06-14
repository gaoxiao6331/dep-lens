import * as fs from "node:fs/promises";
import * as path from "node:path";
import type * as vscode from "vscode";

export interface GoDependency {
  owner: string;
  repo: string;
  line: number;
  character: number;
}

interface WasmExports {
  memory: {
    buffer: ArrayBuffer;
  };
  dep_lens_alloc(len: number): number;
  dep_lens_dealloc(ptr: number, len: number): void;
  dep_lens_parse_go_dependencies_json(
    textPtr: number,
    textLen: number,
    fileNamePtr: number,
    fileNameLen: number,
    startLine: number,
    endLine: number,
  ): number;
  dep_lens_last_result_len(): number;
}

export class GoDependencyParser {
  private static wasmExports: Promise<WasmExports> | undefined;

  static async parse(
    context: vscode.ExtensionContext,
    document: vscode.TextDocument,
    range: vscode.Range,
  ): Promise<GoDependency[]> {
    const wasm = await this.loadWasm(context);
    return this.parseWithWasm(wasm, document.getText(), document.fileName, range);
  }

  private static loadWasm(context: vscode.ExtensionContext): Promise<WasmExports> {
    if (!this.wasmExports) {
      this.wasmExports = this.loadWasmInner(context).catch((error) => {
        // Allow future calls to retry if the current load attempt fails.
        this.wasmExports = undefined;
        throw error;
      });
    }
    return this.wasmExports;
  }

  private static async loadWasmInner(context: vscode.ExtensionContext): Promise<WasmExports> {
    const candidates = [
      path.join(
        context.extensionPath,
        "..",
        "lib",
        "target",
        "wasm32-unknown-unknown",
        "release",
        "dep_lens_lib.wasm",
      ),
      path.join(context.extensionPath, "lib", "dep_lens_lib.wasm"),
    ];
    const errors: string[] = [];

    for (const candidate of candidates) {
      try {
        const bytes = await fs.readFile(candidate);
        const wasm = await instantiateWasm(bytes, {});
        return wasm.instance.exports as unknown as WasmExports;
      } catch (error) {
        errors.push(`${candidate}: ${String(error)}`);
      }
    }

    throw new Error(`Failed to load Dep Lens Go parser WASM:\n${errors.join("\n")}`);
  }

  private static parseWithWasm(
    wasm: WasmExports,
    text: string,
    fileName: string,
    range: vscode.Range,
  ): GoDependency[] {
    const textBytes = new TextEncoder().encode(text);
    const fileNameBytes = new TextEncoder().encode(fileName);
    const textPtr = this.writeBytes(wasm, textBytes);
    const fileNamePtr = this.writeBytes(wasm, fileNameBytes);

    try {
      const resultPtr = wasm.dep_lens_parse_go_dependencies_json(
        textPtr,
        textBytes.length,
        fileNamePtr,
        fileNameBytes.length,
        range.start.line,
        range.end.line,
      );
      const resultLen = wasm.dep_lens_last_result_len();
      const resultBytes = new Uint8Array(wasm.memory.buffer, resultPtr, resultLen);
      return JSON.parse(new TextDecoder().decode(resultBytes)) as GoDependency[];
    } finally {
      wasm.dep_lens_dealloc(textPtr, textBytes.length);
      wasm.dep_lens_dealloc(fileNamePtr, fileNameBytes.length);
    }
  }

  private static writeBytes(wasm: WasmExports, bytes: Uint8Array): number {
    const ptr = wasm.dep_lens_alloc(bytes.length);
    new Uint8Array(wasm.memory.buffer, ptr, bytes.length).set(bytes);
    return ptr;
  }
}

declare const WebAssembly: {
  instantiate(
    bytes: Uint8Array,
    imports?: Record<string, unknown>,
  ): Promise<{ instance: { exports: Record<string, unknown> } }>;
};

function instantiateWasm(
  bytes: Uint8Array,
  imports: Record<string, unknown>,
): Promise<{ instance: { exports: Record<string, unknown> } }> {
  return WebAssembly.instantiate(bytes, imports);
}
