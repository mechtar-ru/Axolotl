// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "AxolotlApp",
    platforms: [.iOS(.v17)],
    products: [
        .library(name: "AxolotlApp", targets: ["App"]),
    ],
    targets: [
        .target(name: "App"),
    ]
)
