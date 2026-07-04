import UIKit

/// An inline chapter-boundary panel in the continuous feed (between a finished chapter and the next),
/// mirroring the Compose reader's `NextChapterOverlay`. When there is no next chapter it renders the
/// terminal "you're at the last chapter" message instead.
final class ReaderBoundaryCell: UICollectionViewCell {
    static let reuseID = "ReaderBoundaryCell"

    private let finishedLabel = UILabel()
    private let titleLabel = UILabel()
    private let nextLabel = UILabel()
    private let icon = UIImageView(image: UIImage(systemName: "arrow.down.circle"))

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = UIColor(white: 0.08, alpha: 1)

        finishedLabel.font = .systemFont(ofSize: 13)
        finishedLabel.textColor = UIColor.white.withAlphaComponent(0.6)
        finishedLabel.textAlignment = .center
        finishedLabel.numberOfLines = 2

        titleLabel.font = .systemFont(ofSize: 13)
        titleLabel.textColor = UIColor.white.withAlphaComponent(0.6)
        titleLabel.textAlignment = .center

        nextLabel.font = .boldSystemFont(ofSize: 18)
        nextLabel.textColor = .white
        nextLabel.textAlignment = .center
        nextLabel.numberOfLines = 2

        icon.tintColor = UIColor.white.withAlphaComponent(0.6)
        icon.contentMode = .scaleAspectFit
        icon.setContentHuggingPriority(.required, for: .vertical)

        let stack = UIStackView(arrangedSubviews: [finishedLabel, icon, titleLabel, nextLabel])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -24),
            stack.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            icon.heightAnchor.constraint(equalToConstant: 28),
            icon.widthAnchor.constraint(equalToConstant: 28),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// - Parameter next: next chapter label, or `nil` for the terminal "last chapter" panel.
    func configure(finished: String, next: String?) {
        finishedLabel.text = finished.isEmpty ? nil : ReaderStrings.finished(finished)
        finishedLabel.isHidden = finished.isEmpty
        if let next = next {
            icon.isHidden = false
            titleLabel.isHidden = false
            titleLabel.text = ReaderStrings.nextChapter
            nextLabel.text = next
        } else {
            icon.isHidden = true
            titleLabel.isHidden = true
            nextLabel.text = ReaderStrings.lastChapter
        }
    }
}
