// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "flutter_webrtc",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(name: "flutter-webrtc", targets: ["flutter_webrtc"]),
        // Lets dependent plugins (e.g. livekit_client) import WebRTC without
        // declaring a second copy of the binary target.
        .library(name: "WebRTC", targets: ["WebRTC"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework")
    ],
    targets: [
        .binaryTarget(
            name: "WebRTC",
            url: "https://github.com/webrtc-sdk/Specs/releases/download/144.7559.15/WebRTC.xcframework.zip",
            checksum: "a17a6688e208255b70381926110526593ba626e033cf88c9ed4a7b618f98eb8a"
        ),
        .target(
            name: "flutter_webrtc",
            dependencies: [
                "WebRTC",
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ],
            cSettings: [
                .headerSearchPath("include/flutter_webrtc")
            ],
            linkerSettings: [
                .linkedLibrary("c++")
            ]
        )
    ],
    cxxLanguageStandard: .cxx14
)
