import XCTest
@testable import CruxCoach

/// A tapped notification opens only the places the app's own notifications name, like Android's
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

    /// Android's `announcements` and `dev_chat` (the background poll's announcement and reply
    /// notifications) open the developer contact page, scrolled to the matching part.
    func testAnnouncementAndReplyRoutesOpenTheDeveloperContact() {
        let router = AppRouter()
        router.open("announcements")
        XCTAssertEqual(router.pending, "announcements")
        XCTAssertEqual(router.take(), "announcements")
        XCTAssertEqual(router.contactFocus, "announcements")
        XCTAssertTrue(AppRouter.opensContact("announcements"))
        router.open("dev_chat")
        XCTAssertEqual(router.take(), "dev_chat")
        XCTAssertEqual(router.contactFocus, "dev_chat")
        XCTAssertTrue(AppRouter.opensContact("dev_chat"))
        // Not a training-area route: an open training area keeps its tab.
        XCTAssertEqual(router.todayRequests, 0)
        XCTAssertFalse(AppRouter.opensContact("today"))
        XCTAssertFalse(AppRouter.opensContact("body"))
    }

    func testUnknownRoutesAreIgnored() {
        let router = AppRouter()
        for route in ["", "settings", "board_sync?localDbUrl=http://10.0.0.1/db", "today ", "TODAY",
                      "message_thread/ab", "DEV_CHAT", "dev_chat/1", "announcements "] {
            router.open(route)
            XCTAssertEqual(router.pending, "", route)
            XCTAssertFalse(AppRouter.opensContact(route), route)
        }
        // An ignored route does not drop one that is already waiting.
        router.open("body")
        router.open("support_settings")
        XCTAssertEqual(router.pending, "body")
    }

    func testTheRouteTravelsUnderAndroidsExtraName() {
        XCTAssertEqual(LocalNotificationScheduler.routeKey, "navigate_to")
    }
}
