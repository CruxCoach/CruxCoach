package com.cruxcoach.app.ui

import com.cruxcoach.app.profile.Nip05Status
import com.cruxcoach.app.profile.ProfileError
import com.cruxcoach.app.profile.ProfilePresenter
import com.cruxcoach.app.profile.ProfileState

class ProfileScreenState(
    val isLoading: Boolean,
    val isPublishing: Boolean,
    val npub: String,
    val displayName: String,
    val about: String,
    val pictureUrl: String,
    val bannerUrl: String,
    val nip05: String,
    val website: String,
    val lightningAddress: String,
    val isDirty: Boolean,
    /** unknown | checking | matches | mismatch | unreachable */
    val nip05StatusCode: String,
    val publishedTo: Int,
    val publishAttempted: Int,
    /** none | loadFailed | noIdentity | signingFailed | noRelayAccepted | invalidUrl */
    val errorCode: String,
)

/** Swift-facing editor for the user's own Nostr profile (kind 0). */
class ProfileScreenModel(private val presenter: ProfilePresenter) {

    val currentState: ProfileScreenState get() = map(presenter.state.value)

    fun watch(onState: (ProfileScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun load() = presenter.load()
    fun refresh() = presenter.refresh()
    fun setDisplayName(value: String) = presenter.setDisplayName(value)
    fun setAbout(value: String) = presenter.setAbout(value)
    fun setPictureUrl(value: String) = presenter.setPictureUrl(value)
    fun setBannerUrl(value: String) = presenter.setBannerUrl(value)
    fun setNip05(value: String) = presenter.setNip05(value)
    fun setWebsite(value: String) = presenter.setWebsite(value)
    fun setLightningAddress(value: String) = presenter.setLightningAddress(value)
    fun saveLocal() = presenter.saveLocal()
    fun publish() = presenter.publish()
    fun verifyNip05() = presenter.verifyNip05()
    fun consumeError() = presenter.consumeError()
    fun close() = presenter.close()

    private fun map(state: ProfileState) = ProfileScreenState(
        isLoading = state.isLoading,
        isPublishing = state.isPublishing,
        npub = state.npub,
        displayName = state.displayName,
        about = state.about,
        pictureUrl = state.pictureUrl,
        bannerUrl = state.bannerUrl,
        nip05 = state.nip05,
        website = state.website,
        lightningAddress = state.lightningAddress,
        isDirty = state.isDirty,
        nip05StatusCode = when (state.nip05Status) {
            Nip05Status.UNKNOWN -> "unknown"
            Nip05Status.CHECKING -> "checking"
            Nip05Status.MATCHES -> "matches"
            Nip05Status.MISMATCH -> "mismatch"
            Nip05Status.UNREACHABLE -> "unreachable"
        },
        publishedTo = state.publishedTo,
        publishAttempted = state.publishAttempted,
        errorCode = when (state.error) {
            ProfileError.NONE -> "none"
            ProfileError.LOAD_FAILED -> "loadFailed"
            ProfileError.NO_IDENTITY -> "noIdentity"
            ProfileError.SIGNING_FAILED -> "signingFailed"
            ProfileError.NO_RELAY_ACCEPTED -> "noRelayAccepted"
            ProfileError.INVALID_URL -> "invalidUrl"
        },
    )
}
