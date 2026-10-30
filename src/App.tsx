/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

export default function App() {
  return (
    <div className="min-h-screen bg-slate-900 text-slate-100 p-8 flex flex-col items-center justify-center font-sans">
      <div className="max-w-2xl bg-slate-800 border border-slate-700 rounded-2xl p-8 shadow-2xl text-center">
        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-emerald-500/10 text-emerald-400 text-xs font-semibold border border-emerald-500/20 mb-4">
          <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
          Phase 1 Complete
        </div>
        <h1 className="text-2xl font-bold mb-3 text-indigo-400">Novel Reader Multi-Voice TTS Engine</h1>
        <p className="text-sm text-slate-300 leading-relaxed mb-6">
          Native Android module architecture (package <code>com.novelreader.app</code>). Phase 1 delivers the Gradle version catalog, multi-module project structure, <code>:tts-core</code> domain models, <code>NovelSegmenter</code> with comprehensive quote/offset support, and a complete suite of unit tests.
        </p>
        <div className="grid grid-cols-3 gap-3 text-left">
          <div className="p-3 bg-slate-900/60 rounded-xl border border-slate-700/60">
            <span className="text-xs text-indigo-400 font-semibold block mb-1">:tts-core</span>
            <p className="text-xs text-slate-400">Pure Kotlin JVM: models, segmenter, character resolution, undo tokens.</p>
          </div>
          <div className="p-3 bg-slate-900/60 rounded-xl border border-slate-700/60">
            <span className="text-xs text-indigo-400 font-semibold block mb-1">:tts-android</span>
            <p className="text-xs text-slate-400">Android library target (minSdk 26, compileSdk 34, TTS service queries).</p>
          </div>
          <div className="p-3 bg-slate-900/60 rounded-xl border border-slate-700/60">
            <span className="text-xs text-indigo-400 font-semibold block mb-1">:sample</span>
            <p className="text-xs text-slate-400">Sample Jetpack Compose mini-reader app structure.</p>
          </div>
        </div>
      </div>
    </div>
  );
}
