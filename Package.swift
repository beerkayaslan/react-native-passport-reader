// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ReactNativePassportReader",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(
            name: "ReactNativePassportReader",
            targets: ["ReactNativePassportReader"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/AndyQ/NFCPassportReader.git", from: "2.1.2"),
    ],
    targets: [
        .target(
            name: "ReactNativePassportReader",
            dependencies: [
                .product(name: "NFCPassportReader", package: "NFCPassportReader"),
            ],
            path: "ios"
        )
    ]
)
