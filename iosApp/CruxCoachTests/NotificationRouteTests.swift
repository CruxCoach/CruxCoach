import XCTest
@testable import CruxCoach

/// A tapped notification opens only the places the app's own reminders name, like Android's
/// `MainActivity.safeNavigateToRoute`, and each route is handed out once.
final class NotificationRouteTests: XCTestCase {

    func testKnownRoutesWaitUntilTakenOnce() {
        let router = AppRouter()
        router.open("today")
        XCTAssertEqual(router.pending, "today")
        XCTAssertEqual(router.take(), "today")
        XCTAssertEqual(router.pending, "")
        XCTAssertEqual(router.todayRequests, 1)
        // Nothing waiting: nothing handed out, nothing counted.
        XCTAssertEqual(router.take(), "")
        XCTAssertEqual(router.todayRequests, 1)
        router.open("body")
        XCTAssertEqual(router.take(), "body")
        XCTAssertEqual(router.todayRequests, 2)
    }

    func testUnknownRoutesAreIgnored() {
        let router = AppRouter()
        for route in ["", "settings", "board_sync?localDbUrl=http://10.0.0.1/db", "today ", "TODAY", "message_thread/ab"] {
            router.open(route)
            XCTAssertEqual(router.pending, "", route)
        }
        // An ignored route does not drop one that is already waiting.
        router.open("body")
        router.open("dev_chat")
        XCTAssertEqual(router.pending, "body")
    }

    func testTheRouteTravelsUnderAndroidsExtraName() {
        XCTAssertEqual(LocalNotificationScheduler.routeKey, "navigate_to")
    }
}
