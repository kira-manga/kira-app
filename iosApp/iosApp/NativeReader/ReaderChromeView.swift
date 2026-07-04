import UIKit

/// UIKit reader chrome: a **top** bar (back / title / bookmark / mode) and a **bottom** bar (chapter
/// nav + page HUD + scrubber + share), installed as two overlays pinned to the top and bottom of the
/// host view.
///
/// **Liquid Glass.** Both bars are `UIVisualEffectView`s carrying `UIGlassEffect` (iOS 26) so the
/// controls sit on a real, adaptive iOS glass surface instead of the old flat black-translucent panel;
/// pre-iOS-26 they fall back to a system material (`.systemUltraThinMaterial`), which is still adaptive
/// and native — never the black overlay. The top bar spans the width and backs the status bar (rounded
/// bottom corners); the bottom bar is a **floating**, fully-rounded glass panel inset from the edges and
/// lifted above the home indicator — the iOS-26 floating-controls look. Symbols/labels use adaptive
/// (`.label` / `.secondaryLabel`) colors so they stay legible as the glass adapts.
///
/// UIKit — not SwiftUI — on purpose: a full-screen `UIHostingController` overlay does not reliably pass
/// touches through its transparent regions, which previously ate the scroll pan and the tap-to-toggle.
/// Here the **middle is never covered**, so the scroll view always owns the pan, and tap-to-toggle (a
/// recognizer on the scroll view) always works. Hidden bars are non-interactive.
final class ReaderChromeBars {
    var onBack: () -> Void = {}
    var onToggleBookmark: () -> Void = {}
    var onShare: () -> Void = {}
    var onPrevChapter: () -> Void = {}
    var onNextChapter: () -> Void = {}
    var onSeek: (Int) -> Void = { _ in }
    var onPickReadingMode: () -> Void = {}

    private let topBar = UIVisualEffectView()
    private let bottomBar = UIVisualEffectView()
    private let titleLabel = UILabel()
    private let chapterLabel = UILabel()
    private let backButton = button("chevron.backward")
    private let modeButton = button("book")
    private let bookmarkButton = button("bookmark")
    private let prevButton = button("chevron.left.2")
    private let nextButton = button("chevron.right.2")
    private let shareButton = button("square.and.arrow.up")
    private let pageLabel = UILabel()
    private let slider = UISlider()
    private var seeking = false
    private(set) var visible = true

    func install(in container: UIView) {
        let safe = container.safeAreaLayoutGuide

        // Glass surfaces. Top backs the status bar (rounded bottom corners); bottom floats (all corners).
        applyGlass(to: topBar)
        applyGlass(to: bottomBar)
        topBar.layer.cornerRadius = 22
        topBar.layer.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
        bottomBar.layer.cornerRadius = 26
        for bar in [topBar, bottomBar] {
            bar.layer.cornerCurve = .continuous
            bar.clipsToBounds = true
            bar.translatesAutoresizingMaskIntoConstraints = false
            container.addSubview(bar)
        }

        // Top bar
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold); titleLabel.textColor = .label; titleLabel.numberOfLines = 1
        chapterLabel.font = .systemFont(ofSize: 12); chapterLabel.textColor = .secondaryLabel; chapterLabel.numberOfLines = 1
        let titleStack = UIStackView(arrangedSubviews: [titleLabel, chapterLabel])
        titleStack.axis = .vertical; titleStack.spacing = 1
        backButton.addTarget(self, action: #selector(tapBack), for: .touchUpInside)
        bookmarkButton.addTarget(self, action: #selector(tapBookmark), for: .touchUpInside)
        modeButton.addTarget(self, action: #selector(tapMode), for: .touchUpInside)
        let topStack = UIStackView(arrangedSubviews: [backButton, titleStack, modeButton, bookmarkButton])
        topStack.axis = .horizontal; topStack.spacing = 8; topStack.alignment = .center
        topStack.translatesAutoresizingMaskIntoConstraints = false
        topBar.contentView.addSubview(topStack)

        // Bottom bar
        pageLabel.font = .monospacedDigitSystemFont(ofSize: 13, weight: .medium); pageLabel.textColor = .label
        prevButton.addTarget(self, action: #selector(tapPrev), for: .touchUpInside)
        nextButton.addTarget(self, action: #selector(tapNext), for: .touchUpInside)
        shareButton.addTarget(self, action: #selector(tapShare), for: .touchUpInside)
        slider.minimumTrackTintColor = .label; slider.maximumTrackTintColor = .tertiaryLabel
        slider.addTarget(self, action: #selector(sliderChanged), for: .valueChanged)
        slider.addTarget(self, action: #selector(sliderDown), for: .touchDown)
        slider.addTarget(self, action: #selector(sliderUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        // Tap anywhere on the track to jump there (parity with Android) — UISlider only handles thumb drag.
        slider.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(sliderTapped)))
        let scrubRow = UIStackView(arrangedSubviews: [prevButton, slider, nextButton])
        scrubRow.axis = .horizontal; scrubRow.spacing = 10; scrubRow.alignment = .center
        let hudRow = UIStackView(arrangedSubviews: [pageLabel, UIView(), shareButton])
        hudRow.axis = .horizontal; hudRow.alignment = .center
        let bottomStack = UIStackView(arrangedSubviews: [scrubRow, hudRow])
        bottomStack.axis = .vertical; bottomStack.spacing = 2
        bottomStack.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.contentView.addSubview(bottomStack)

        NSLayoutConstraint.activate([
            // Top bar spans the width and reaches the very top so its glass backs the status bar; content
            // is pinned inside the safe area.
            topBar.topAnchor.constraint(equalTo: container.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            topStack.topAnchor.constraint(equalTo: safe.topAnchor, constant: 6),
            topStack.leadingAnchor.constraint(equalTo: topBar.contentView.leadingAnchor, constant: 12),
            topStack.trailingAnchor.constraint(equalTo: topBar.contentView.trailingAnchor, constant: -12),
            topStack.heightAnchor.constraint(equalToConstant: 44),
            topBar.bottomAnchor.constraint(equalTo: topStack.bottomAnchor, constant: 10),

            // Bottom bar floats: inset from both edges and lifted above the home indicator.
            bottomBar.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
            bottomBar.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12),
            bottomBar.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -8),
            bottomStack.topAnchor.constraint(equalTo: bottomBar.contentView.topAnchor, constant: 12),
            bottomStack.leadingAnchor.constraint(equalTo: bottomBar.contentView.leadingAnchor, constant: 16),
            bottomStack.trailingAnchor.constraint(equalTo: bottomBar.contentView.trailingAnchor, constant: -16),
            bottomStack.bottomAnchor.constraint(equalTo: bottomBar.contentView.bottomAnchor, constant: -12),
        ])
    }

    func update(title: String, chapter: String, page: Int, count: Int, bookmarked: Bool, canPrev: Bool, canNext: Bool) {
        titleLabel.text = title
        chapterLabel.text = chapter
        chapterLabel.isHidden = chapter.isEmpty
        pageLabel.text = "\(page) / \(count)"
        bookmarkButton.setImage(UIImage(systemName: bookmarked ? "bookmark.fill" : "bookmark"), for: .normal)
        prevButton.isEnabled = canPrev
        nextButton.isEnabled = canNext
        prevButton.alpha = canPrev ? 1 : 0.3
        nextButton.alpha = canNext ? 1 : 0.3
        let scrubVisible = count > 1
        slider.isHidden = !scrubVisible
        prevButton.isHidden = !scrubVisible
        nextButton.isHidden = !scrubVisible
        if scrubVisible {
            slider.maximumValue = Float(max(count - 1, 1))
            if !seeking { slider.value = Float(max(page - 1, 0)) }
        }
    }

    func setVisible(_ v: Bool) {
        visible = v
        let alpha: CGFloat = v ? 1 : 0
        UIView.animate(withDuration: 0.2) {
            self.topBar.alpha = alpha
            self.bottomBar.alpha = alpha
        }
        topBar.isUserInteractionEnabled = v
        bottomBar.isUserInteractionEnabled = v
    }

    @objc private func tapBack() { onBack() }
    @objc private func tapBookmark() { onToggleBookmark() }
    @objc private func tapMode() { onPickReadingMode() }
    @objc private func tapShare() { onShare() }
    @objc private func tapPrev() { onPrevChapter() }
    @objc private func tapNext() { onNextChapter() }
    @objc private func sliderDown() { seeking = true }
    @objc private func sliderUp() { seeking = false; onSeek(Int(slider.value.rounded())) }
    @objc private func sliderChanged() { onSeek(Int(slider.value.rounded())) }

    @objc private func sliderTapped(_ g: UITapGestureRecognizer) {
        guard slider.maximumValue > slider.minimumValue else { return }
        let pct = max(0, min(1, g.location(in: slider).x / max(slider.bounds.width, 1)))
        let value = slider.minimumValue + Float(pct) * (slider.maximumValue - slider.minimumValue)
        slider.setValue(value, animated: true)
        onSeek(Int(value.rounded()))
    }

    /// Liquid Glass surface (iOS 26); adaptive system material as the pre-26 fallback (still native, never
    /// the flat black overlay). Non-interactive: the bar hosts its own buttons, so the glass shouldn't
    /// deform on touch (that fluid response is for direct-manipulation glass controls, not a container).
    private func applyGlass(to view: UIVisualEffectView) {
        if #available(iOS 26.0, *) {
            let glass = UIGlassEffect(style: .regular)
            glass.isInteractive = false
            view.effect = glass
        } else {
            view.effect = UIBlurEffect(style: .systemUltraThinMaterial)
        }
    }

    private static func button(_ symbol: String) -> UIButton {
        let b = UIButton(type: .system)
        b.setImage(UIImage(systemName: symbol), for: .normal)
        b.setPreferredSymbolConfiguration(UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold), forImageIn: .normal)
        b.tintColor = .label
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: 44).isActive = true
        b.heightAnchor.constraint(equalToConstant: 44).isActive = true
        return b
    }
}
