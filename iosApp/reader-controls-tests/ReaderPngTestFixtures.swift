import Foundation

/// Tiny generated protocol fixtures; Python/zlib byte sanity is not a Swift/ImageIO test result.
enum ReaderPngTestFixtures {
    struct Valid {
        let name: String
        let data: Data
        let width: UInt32
        let height: UInt32
    }
    struct Invalid {
        let name: String
        let data: Data
    }

    static let valid: [Valid] = [
        Valid(name: "split-header-adler-gray1",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAElEQVQ1rwYeAAAAAUlEQVR4duaE5gAAAAFJREFUAV8/TX4AAAAASURBVDWvBh4AAAAXSURBVAESAO3/AAABAAIAAwAEAAAAAQACAAMA4LWKygAAAAFJREFUACg4fegAAAACSURBVJ4AkzkaLgAAAAFJREFUEUKIXRoAAAAASURBVDWvBh4AAAAASUVORK5CYII="), width: 8, height: 9),
        Valid(name: "packed-gray2",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACAgAAAADyryFnAAAAD0lEQVR4AQEEAPv/AAABAAAGAAI0mJURAAAAAElFTkSuQmCC"), width: 3, height: 2),
        Valid(name: "packed-gray4",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACBAAAAAB979THAAAAEUlEQVR4AQEGAPn/AAAAAQAAAAkAApj2SMkAAAAASUVORK5CYII="), width: 3, height: 2),
        Valid(name: "gray16",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACEAAAAAAHTY67AAAAFUlEQVR4AQEKAPX/AAAAAAABAAAAAAAPAAKPnq5GAAAAAElFTkSuQmCC"), width: 2, height: 2),
        Valid(name: "rgb16",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACEAIAAACtREYwAAAAJUlEQVR4AQEaAOX/AAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAJwACTUhvcAAAAABJRU5ErkJggg=="), width: 2, height: 2),
        Valid(name: "gray-alpha16",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACEAQAAACILxnsAAAAHUlEQVR4AQESAO3/AAAAAAAAAAAAAQAAAAAAAAAAABsAAsLAOjwAAAAASUVORK5CYII="), width: 2, height: 2),
        Valid(name: "rgba16",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACEAYAAAAiJtFnAAAALUlEQVR4AQEiAN3/AAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAzAAIELfESAAAAAElFTkSuQmCC"), width: 2, height: 2),
        Valid(name: "indexed2-palette-transparency",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACAgMAAADgGo6JAAAABlBMVEX/AAAA/wDSh+9xAAAAAnRSTlP/gAgPs2oAAAAPSURBVHgBAQQA+/8AAAEAAAYAAjSYlREAAAAASUVORK5CYII="), width: 3, height: 2),
        Valid(name: "adam7-empty-passes",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABAQAAAAFAacmyAAAADUlEQVR4AQECAP3/AAAAAgABfgUN0gAAAABJRU5ErkJggg=="), width: 1, height: 1),
        Valid(name: "adam7-packed-all-passes",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAFQL2AVAAAAMUlEQVR4AQEmANn/AAABAAIAAwAEAAAAAQACAAMABAAAAAEAAgADAAQAAAABAAIAAwACzgAlvi3aCwAAAABJRU5ErkJggg=="), width: 8, height: 9),
        Valid(name: "above12k-source-edge",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAPoEAAAABAQAAAACrd5A/AAAAF0lEQVR42mNgGAWjYBSMglEwCkbB0AcAB9IAAahUjrQAAAAASUVORK5CYII="), width: 16001, height: 1),
        Valid(name: "scratch-splits-row-payload",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAQAAAAAADCAAAAABOPEiPAAAATUlEQVR42u3QMQEAAAwCILf+oY3hAxFIAAAAAAAAAAAAAAAAAAAAgLlTAAAAAAAAAAAAAAAAAAAAAHuvAAAAAAAAAAAAAAAAAAAAAPYKwBYABFsGKxIAAAAASUVORK5CYII="), width: 16384, height: 3),
        Valid(name: "scratch-ends-at-filter",
              data: bytes("iVBORw0KGgoAAAANSUhEUgAAf/8AAAACCAAAAADftfPkAAAAV0lEQVR42u3QgQAAAADDoN1f+iKFUAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHYAPAAJANgS5AAAAAElFTkSuQmCC"), width: 32767, height: 2),
    ]

    static let framingFailures: [Invalid] = [
        Invalid(name: "bad-idat-crc",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDswAAAAASUVORK5CYII=")),
        Invalid(name: "missing-iend",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0=")),
        Invalid(name: "trailing-after-iend",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYIIA")),
        Invalid(name: "nonempty-iend",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAABSUVORADRGk/h")),
        Invalid(name: "duplicate-ihdr",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAADUlIRFIAAAAIAAAACQEAAAAAJyhQgwAAAB1JREFUeAEBEgDt/wAAAQACAAMABAAAAAEAAgADAACeABEHsg7NAAAAAElFTkSuQmCC")),
        Invalid(name: "nonconsecutive-idat",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAklEQVR4AewaftIAAAADdEVYdGsAdssE85AAAAAbSURBVAESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEXCTQqUAAAAASUVORK5CYII=")),
        Invalid(name: "unknown-critical",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAEFCQ0TbFyClAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "reserved-type-bit",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAHRleFQA5YcPAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "indexed-missing-palette",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQMAAAA1nf9tAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "palette-after-idat",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJCAIAAACAMfp5AAAAG0lEQVR42mNgwAEYcUkw4ZJgxiXBwkAqoKLlAAe3ABGjIg/RAAAAA1BMVEUAAACnej3aAAAAAElFTkSuQmCC")),
        Invalid(name: "transparency-with-alpha",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJCAYAAAAPU20uAAAAAXRSTlMAQObYZgAAABxJREFUeNpjYCAAGAkpYCKkgJmQAhYGSgEdHAkACi8AEU/kgOUAAAAASUVORK5CYII=")),
        Invalid(name: "illegal-depth-color",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQYAAAACQw9fAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "nonzero-compression-method",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQABAAAm6jq0AAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "nonzero-filter-method",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAQA+M2HCAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
        Invalid(name: "invalid-interlace",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAALJJjGvAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQeyDs0AAAAASUVORK5CYII=")),
    ]

    static let streamFailures: [Invalid] = [
        Invalid(name: "valid-header-reserved-deflate-block",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAB0lEQVR4AQcAngARcpD/1QAAAABJRU5ErkJggg==")),
        Invalid(name: "bad-adler",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEHC1PlsAAAAASUVORK5CYII=")),
        Invalid(name: "short-filtered-rows",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHElEQVR4AQERAO7/AAABAAIAAwAEAAAAAQACAAMAjQAR/zck6AAAAABJRU5ErkJggg==")),
        Invalid(name: "long-filtered-rows",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHklEQVR4AQETAOz/AAABAAIAAwAEAAAAAQACAAMAAACvABFKBefpAAAAAElFTkSuQmCC")),
        Invalid(name: "invalid-row-filter",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHUlEQVR4AQESAO3/BQABAAIAAwAEAAAAAQACAAMAAPgAFp4F2zwAAAAASUVORK5CYII=")),
        Invalid(name: "truncated-zlib-checksum",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHElEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4A0KCkMwAAAABJRU5ErkJggg==")),
        Invalid(name: "trailing-compressed-byte",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAHklEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEQA30OOOAAAAAElFTkSuQmCC")),
        Invalid(name: "second-zlib-stream",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAOklEQVR4AQESAO3/AAABAAIAAwAEAAAAAQACAAMAAJ4AEXgBARIA7f8AAAEAAgADAAQAAAABAAIAAwAAngAREfjuSAAAAABJRU5ErkJggg==")),
        Invalid(name: "preset-dictionary",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAABklEQVR4IAAAAAFhQYn+AAAAAElFTkSuQmCC")),
        Invalid(name: "header-without-deflate-progress",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAklEQVR4AewaftIAAAAASUVORK5CYII=")),
        Invalid(name: "empty-idat",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAJAQAAAAAnKFCDAAAAAElEQVQ1rwYeAAAAAElFTkSuQmCC")),
    ]

    static let workFailures: [Invalid] = [
        Invalid(name: "filtered-work-one-row-over",
                data: bytes("iVBORw0KGgoAAAANSUhEUgAAAAEgAAABAQAAAAC1n3uHAAAAC0lEQVR4AQEAAP//AAAAAYnWrl8AAAAASUVORK5CYII=")),
        Invalid(name: "checked-huge-rgba16",
                data: bytes("iVBORw0KGgoAAAANSUhEUn////9/////EAYAAABEWdclAAAAC0lEQVR4AQEAAP//AAAAAYnWrl8AAAAASUVORK5CYII=")),
    ]

    private static func bytes(_ encoded: String) -> Data { Data(base64Encoded: encoded)! }
}
