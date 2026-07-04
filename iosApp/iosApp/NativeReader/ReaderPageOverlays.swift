import UIKit

/// Per-page loading indicator: an indeterminate spinner that upgrades to a determinate ring + "NN%" once
/// the loader reports a download fraction (parity with the Compose reader's determinate/indeterminate
/// `CircularProgressIndicator`). Shared by the webtoon and paged cells.
final class ReaderPageProgressView: UIView {
    private let spinner = UIActivityIndicatorView(style: .large)
    private let trackLayer = CAShapeLayer()
    private let progressLayer = CAShapeLayer()
    private let percentLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: 56).isActive = true
        heightAnchor.constraint(equalToConstant: 56).isActive = true

        spinner.color = .white
        spinner.hidesWhenStopped = true
        spinner.transform = CGAffineTransform(scaleX: 1.4, y: 1.4)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        addSubview(spinner)

        for shape in [trackLayer, progressLayer] {
            shape.fillColor = UIColor.clear.cgColor
            shape.lineWidth = 4
            shape.lineCap = .round
            layer.addSublayer(shape)
        }
        trackLayer.strokeColor = UIColor.white.withAlphaComponent(0.25).cgColor
        progressLayer.strokeColor = UIColor.white.cgColor
        progressLayer.strokeEnd = 0
        progressLayer.actions = ["strokeEnd": NSNull()] // no implicit animation per update

        percentLabel.font = .systemFont(ofSize: 12, weight: .medium)
        percentLabel.textColor = .white
        percentLabel.textAlignment = .center
        percentLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(percentLabel)

        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: centerYAnchor),
            percentLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            percentLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
        showRing(false)
        isHidden = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let r = min(bounds.width, bounds.height) / 2 - 3
        let path = UIBezierPath(arcCenter: CGPoint(x: bounds.midX, y: bounds.midY), radius: r,
                                startAngle: -.pi / 2, endAngle: .pi * 1.5, clockwise: true).cgPath
        trackLayer.path = path; trackLayer.frame = bounds
        progressLayer.path = path; progressLayer.frame = bounds
    }

    func startIndeterminate() {
        isHidden = false
        showRing(false)
        spinner.startAnimating()
    }

    /// `fraction` in 0…1. Near-0 or near-1 falls back to the spinner to avoid an empty/full-ring flash.
    func setFraction(_ fraction: Double) {
        guard fraction > 0.01, fraction < 0.99 else {
            if fraction >= 0.99 { hide() } else { startIndeterminate() }
            return
        }
        isHidden = false
        spinner.stopAnimating()
        showRing(true)
        progressLayer.strokeEnd = CGFloat(fraction)
        percentLabel.text = "\(Int(fraction * 100))%"
    }

    func hide() {
        isHidden = true
        spinner.stopAnimating()
    }

    private func showRing(_ show: Bool) {
        trackLayer.isHidden = !show
        progressLayer.isHidden = !show
        percentLabel.isHidden = !show
    }
}

/// Per-page error slot: a message with **Retry** and **Open in WebView** actions (the latter opens the
/// current chapter in the WebView for Cloudflare recovery, via the shared reader effect path).
final class ReaderPageErrorView: UIView {
    var onRetry: (() -> Void)?
    var onOpenInWebView: (() -> Void)?

    private let label = UILabel()
    private let retryButton = UIButton(type: .system)
    private let webButton = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.text = ReaderStrings.failedToLoadImage
        label.textColor = .lightGray
        label.font = .systemFont(ofSize: 14)
        label.numberOfLines = 0
        label.textAlignment = .center
        retryButton.setTitle(ReaderStrings.retry, for: .normal)
        retryButton.tintColor = .white
        retryButton.addTarget(self, action: #selector(retry), for: .touchUpInside)
        webButton.setTitle(ReaderStrings.openInWebView, for: .normal)
        webButton.tintColor = .white
        webButton.addTarget(self, action: #selector(web), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [label, retryButton, webButton])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func retry() { onRetry?() }
    @objc private func web() { onOpenInWebView?() }
}
