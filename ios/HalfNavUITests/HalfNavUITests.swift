import CoreLocation
import XCTest

/// End-to-end checks that drive the app like a person would, with simulated GPS.
/// Screenshots go to $HALFNAV_SHOTS (default /tmp/halfnav-shots) for review.
final class HalfNavUITests: XCTestCase {
    private var app: XCUIApplication!
    private let shotsDir = ProcessInfo.processInfo.environment["HALFNAV_SHOTS"] ?? "/tmp/halfnav-shots"

    // New Braunfels, TX.
    private let newBraunfels = CLLocation(latitude: 29.7030, longitude: -98.1245)

    override func setUp() {
        continueAfterFailure = false
        try? FileManager.default.createDirectory(atPath: shotsDir, withIntermediateDirectories: true)
        app = XCUIApplication()
    }

    private func shot(_ name: String) {
        let data = XCUIScreen.main.screenshot().pngRepresentation
        try? data.write(to: URL(fileURLWithPath: "\(shotsDir)/\(name).png"))
    }

    /// Accept the location prompt if it appears.
    private func allowLocation() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow While Using App", "Allow Once", "Allow"] {
            let b = springboard.buttons[label]
            if b.waitForExistence(timeout: 3) { b.tap(); return }
        }
    }

    private func launch(at location: CLLocation) {
        XCUIDevice.shared.location = XCUILocation(location: location)
        app.launch()
        allowLocation()
        sleep(3)
    }

    private func searchAndPick(_ query: String, containing text: String) {
        let field = app.textFields["Search here"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText(query)
        let result = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 15), "no search result containing \(text)")
        shot("\(query)-results")
        result.tap()
    }

    func testSearchAndRouteCheck() {
        launch(at: newBraunfels)
        shot("01-start")
        searchAndPick("The Alamo", containing: "San Antonio, TX")
        XCTAssertTrue(app.buttons["Go"].waitForExistence(timeout: 10))
        app.buttons["To interstate"].tap()
        shot("02-place")
        app.buttons["Go"].tap()
        let ok = app.staticTexts["Route check: The Alamo"].waitForExistence(timeout: 30)
        sleep(2)
        shot("03-route-check")
        XCTAssertTrue(ok, "route check didn't appear")
    }

    /// Clears any saved home, so the Home button starts with "Set Home".
    private func removeHome() {
        let home = app.buttons["Home"]
        XCTAssertTrue(home.waitForExistence(timeout: 10))
        home.press(forDuration: 1.2)
        let remove = app.buttons["Remove Home"]
        if remove.waitForExistence(timeout: 3) { remove.tap() } else { app.tap() }
    }

    func testSetHomeByAddress() {
        launch(at: newBraunfels)
        removeHome()
        app.buttons["Home"].tap()
        let enter = app.buttons["Enter an Address"]
        XCTAssertTrue(enter.waitForExistence(timeout: 5))
        shot("30-set-home")
        enter.tap()
        let field = app.textFields["Search for your home address"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("386 North Castell Avenue New Braunfels")
        let result = app.buttons.containing(NSPredicate(format: "label CONTAINS %@", "Castell")).firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 15))
        shot("31-home-results")
        result.tap()
        XCTAssertTrue(app.buttons["Go"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Home"].exists)
        shot("32-home-set")

        // Next time, Home goes straight to the saved place.
        app.terminate()
        launch(at: newBraunfels)
        app.buttons["Home"].tap()
        XCTAssertTrue(app.buttons["Go"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Castell'")).firstMatch.exists)
        shot("33-home-again")
    }

    func testSetHomeHere() {
        launch(at: newBraunfels)
        removeHome()
        app.buttons["Home"].tap()
        let here = app.buttons["Use My Location"]
        XCTAssertTrue(here.waitForExistence(timeout: 5))
        here.tap()
        XCTAssertTrue(app.buttons["Go"].waitForExistence(timeout: 30))
        shot("34-home-here")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'New Braunfels'")).firstMatch.exists)
    }

    /// Plays back a real route (lat,lng per line, from $HALFNAV_DRIVE) at one position per second.
    func testGuidedDrive() throws {
        let path = try XCTUnwrap(ProcessInfo.processInfo.environment["HALFNAV_DRIVE"], "set HALFNAV_DRIVE")
        let steps = try String(contentsOfFile: path, encoding: .utf8)
            .split(separator: "\n").filter { !$0.hasPrefix("#") }
            .compactMap { line -> CLLocation? in
                let p = line.split(separator: ",").compactMap { Double($0) }
                return p.count == 2 ? CLLocation(latitude: p[0], longitude: p[1]) : nil
            }
        let limit = Int(ProcessInfo.processInfo.environment["HALFNAV_DRIVE_STEPS"] ?? "") ?? 150

        launch(at: steps[0])
        searchAndPick("The Alamo", containing: "San Antonio, TX")
        app.buttons["To interstate"].tap()
        app.buttons["Go"].tap()
        let guide = app.buttons.containing(NSPredicate(format: "label BEGINSWITH 'Guide me to'")).firstMatch
        XCTAssertTrue(guide.waitForExistence(timeout: 30))
        guide.tap()
        for (i, loc) in steps.prefix(limit).enumerated() {
            XCUIDevice.shared.location = XCUILocation(location: loc)
            Thread.sleep(forTimeInterval: 1)
            if i == 30 { shot("10-drive-start") }
            if i == 90 { shot("11-drive-mid") }
        }
        shot("12-drive-end")
    }

    /// Auto appearance follows the sun where you are: run when it's day in one place and night in the other.
    func testDayAndNight() {
        launch(at: CLLocation(latitude: -33.8688, longitude: 151.2093)) // Sydney
        sleep(5)
        shot("20-sydney")
        app.terminate()
        launch(at: CLLocation(latitude: 29.4241, longitude: -98.4936)) // San Antonio, TX
        sleep(5)
        shot("21-san-antonio")
    }
}
