import AVFoundation

/// Speaks directions with the system voice. Music gets quieter while it talks, and spoken-word
/// audio (podcasts, audiobooks) pauses and resumes, the way navigation apps do.
final class VoiceGuide: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    private let synth = AVSpeechSynthesizer()
    private let session = AVAudioSession.sharedInstance()

    override init() {
        super.init()
        synth.delegate = self
        try? session.setCategory(.playback, mode: .voicePrompt,
                                 options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
    }

    func speak(_ phrases: [String]) {
        guard !phrases.isEmpty else { return }
        try? session.setActive(true)
        for p in phrases {
            NSLog("HalfNavVoice: %@", p)
            let u = AVSpeechUtterance(string: p)
            u.voice = AVSpeechSynthesisVoice(language: "en-US")
            synth.speak(u)
        }
    }

    /// Stop talking right away (e.g. when muted).
    func silence() {
        synth.stopSpeaking(at: .immediate)
        release()
    }

    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        if !s.isSpeaking { release() }
    }

    private func release() {
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }
}
