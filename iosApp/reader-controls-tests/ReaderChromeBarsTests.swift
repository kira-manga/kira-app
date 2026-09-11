import UIKit
import XCTest

final class ReaderChromeBarsTests: XCTestCase {
    @MainActor
    func testSinglePageChapterButtonsKeepEnablementAndEnabledActions() throws {
        let fixture = ChromeFixture(width: 320)
        let previous = try fixture.button(action: "tapPrev")
        let next = try fixture.button(action: "tapNext")
        let slider = try fixture.slider()
        for (canPrev, canNext) in [(false, false), (true, false), (false, true), (true, true)] {
            let previousCalls = fixture.previousCalls
            let nextCalls = fixture.nextCalls
            fixture.update(count: 1, canPrev: canPrev, canNext: canNext)
            fixture.container.layoutIfNeeded()
            assertButton(previous, enabled: canPrev)
            assertButton(next, enabled: canNext)
            XCTAssertTrue(slider.isHidden)
            // sendActions tests the registered callback, not admission of disabled/hidden touches.
            if canPrev { previous.sendActions(for: .touchUpInside) }
            if canNext { next.sendActions(for: .touchUpInside) }
            XCTAssertEqual(fixture.previousCalls, previousCalls + (canPrev ? 1 : 0))
            XCTAssertEqual(fixture.nextCalls, nextCalls + (canNext ? 1 : 0))
        }
    }

    @MainActor
    func testEmptyChapterKeepsChapterButtonsAndSliderHidden() throws {
        let fixture = ChromeFixture(width: 320)
        fixture.update(page: 0, count: 0, canPrev: true, canNext: true)
        XCTAssertTrue(try fixture.button(action: "tapPrev").isHidden)
        XCTAssertTrue(try fixture.button(action: "tapNext").isHidden)
        XCTAssertTrue(try fixture.slider().isHidden)
        XCTAssertEqual(fixture.previousCalls, 0)
        XCTAssertEqual(fixture.nextCalls, 0)
        XCTAssertTrue(fixture.seeks.isEmpty)
    }

    @MainActor
    func testOneManyOneTransitionsKeepFiniteLayoutAndChapterRelativeSeeking() throws {
        for width in [CGFloat(320), CGFloat(390)] {
            let fixture = ChromeFixture(width: width)
            fixture.update(count: 1)
            try assertLayout(fixture, singlePage: true)
            fixture.update(page: 3, count: 5)
            try assertLayout(fixture, singlePage: false)
            let slider = try fixture.slider()
            XCTAssertEqual(slider.minimumValue, 0)
            XCTAssertEqual(slider.maximumValue, 4)
            XCTAssertEqual(slider.value, 2)
            slider.value = 3.6
            slider.sendActions(for: .valueChanged)
            XCTAssertEqual(fixture.seeks, [4])
            fixture.update(page: 2, count: 5)
            XCTAssertEqual(slider.value, 1)
            fixture.update(count: 1)
            try assertLayout(fixture, singlePage: true)
            XCTAssertEqual(fixture.seeks, [4], "A count transition must not create a seek")
        }
    }

    @MainActor
    private func assertButton(_ button: UIButton, enabled: Bool) {
        XCTAssertFalse(button.isHidden)
        XCTAssertEqual(button.isEnabled, enabled)
        XCTAssertEqual(button.alpha, enabled ? 1 : 0.3, accuracy: 0.001)
    }

    @MainActor
    private func assertLayout(_ fixture: ChromeFixture, singlePage: Bool) throws {
        fixture.container.layoutIfNeeded()
        let previous = try fixture.button(action: "tapPrev")
        let next = try fixture.button(action: "tapNext")
        let slider = try fixture.slider()
        let row = try XCTUnwrap(slider.superview as? UIStackView)
        let spacer = try XCTUnwrap(row.arrangedSubviews.first {
            $0 !== previous && $0 !== next && $0 !== slider
        })
        XCTAssertTrue(row.arrangedSubviews.first === previous)
        XCTAssertTrue(row.arrangedSubviews.last === next)
        XCTAssertEqual(slider.isHidden, singlePage)
        XCTAssertEqual(spacer.isHidden, !singlePage)
        XCTAssertFalse(spacer.isUserInteractionEnabled)
        assertButton(previous, enabled: true)
        assertButton(next, enabled: true)
        assertFrames(previous: previous, next: next, middle: singlePage ? spacer : slider,
                     row: row, container: fixture.container)
    }

    @MainActor
    private func assertFrames(previous: UIButton, next: UIButton, middle: UIView,
                              row: UIStackView, container: UIView) {
        let frame = row.convert(row.bounds, to: container)
        let horizontalInset: CGFloat = 12 + 16 // Floating bar inset + its content padding.
        XCTAssertEqual(frame.minX, horizontalInset, accuracy: 0.01)
        XCTAssertEqual(frame.maxX, container.bounds.width - horizontalInset, accuracy: 0.01)
        XCTAssertGreaterThan(row.bounds.width, 0)
        XCTAssertTrue(row.bounds.width.isFinite)
        for button in [previous, next] {
            XCTAssertEqual(button.frame.width, 44, accuracy: 0.01)
            XCTAssertEqual(button.frame.height, 44, accuracy: 0.01)
            XCTAssertTrue(row.bounds.contains(button.frame))
        }
        XCTAssertEqual(previous.frame.minX, row.bounds.minX, accuracy: 0.01)
        XCTAssertEqual(next.frame.maxX, row.bounds.maxX, accuracy: 0.01)
        XCTAssertGreaterThan(middle.frame.width, 0)
        XCTAssertEqual(middle.frame.minX, previous.frame.maxX + row.spacing, accuracy: 0.01)
        XCTAssertEqual(middle.frame.maxX, next.frame.minX - row.spacing, accuracy: 0.01)
        XCTAssertLessThan(previous.frame.maxX, next.frame.minX)
    }
}

/// Installs the real shipping chrome; no replicated layout, visibility or navigation policy.
@MainActor
private final class ChromeFixture {
    let chrome = ReaderChromeBars()
    let container: UIView
    private(set) var previousCalls = 0
    private(set) var nextCalls = 0
    private(set) var seeks: [Int] = []

    init(width: CGFloat) {
        container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 640))
        container.semanticContentAttribute = .forceLeftToRight
        chrome.install(in: container)
        chrome.onPrevChapter = { [weak self] in self?.previousCalls += 1 }
        chrome.onNextChapter = { [weak self] in self?.nextCalls += 1 }
        chrome.onSeek = { [weak self] in self?.seeks.append($0) }
    }

    func update(page: Int = 1, count: Int, canPrev: Bool = true, canNext: Bool = true) {
        chrome.update(title: "Manga", chapter: "Chapter", page: page, count: count,
                      bookmarked: false, canPrev: canPrev, canNext: canNext)
        container.setNeedsLayout()
    }

    func button(action: String) throws -> UIButton {
        try XCTUnwrap(descendants(of: container).compactMap { $0 as? UIButton }.first {
            $0.actions(forTarget: chrome, forControlEvent: .touchUpInside)?.contains(action) == true
        }, "Missing real chapter action: \(action)")
    }

    func slider() throws -> UISlider {
        try XCTUnwrap(descendants(of: container).compactMap { $0 as? UISlider }.first)
    }

    private func descendants(of view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}
