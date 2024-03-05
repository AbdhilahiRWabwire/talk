/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * @author Marcel Hibbe
 * @author Tim Krüger
 * Copyright (C) 2021 Tim Krüger <t@timkrueger.me>
 * Copyright (C) 2021-2022 Marcel Hibbe <dev@mhibbe.de>
 * Copyright (C) 2017-2018 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.talk.utils

import android.net.Uri
import android.text.TextUtils
import android.util.Log
import com.nextcloud.talk.BuildConfig
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.RetrofitBucket
import com.nextcloud.talk.models.json.capabilities.SpreedCapability
import com.nextcloud.talk.utils.CapabilitiesUtil.hasSpreedFeatureCapability
import okhttp3.Credentials.basic
import java.nio.charset.StandardCharsets

@Suppress("TooManyFunctions")
object ApiUtils {
    private val TAG = ApiUtils::class.java.simpleName
    const val API_V1 = 1
    private const val API_V2 = 2
    const val API_V3 = 3
    const val API_V4 = 4
    private const val AVATAR_SIZE_BIG = 512
    private const val AVATAR_SIZE_SMALL = 64
    private const val OCS_API_VERSION = "/ocs/v2.php"
    private const val SPREED_API_VERSION = "/apps/spreed/api/v1"
    private const val SPREED_API_BASE = "$OCS_API_VERSION/apps/spreed/api/v"

    @JvmStatic
    val userAgent = "Mozilla/5.0 (Android) Nextcloud-Talk v"
        get() = field + BuildConfig.VERSION_NAME

    @Deprecated(
        "This is only supported on API v1-3, in API v4+ please use " +
            "{@link ApiUtils#getUrlForAttendees(int, String, String)} instead."
    )
    fun getUrlForRemovingParticipantFromConversation(baseUrl: String?, roomToken: String?, isGuest: Boolean): String {
        var url = getUrlForParticipants(API_V1, baseUrl, roomToken)
        if (isGuest) {
            url += "/guests"
        }
        return url
    }

    private fun getRetrofitBucketForContactsSearch(baseUrl: String, searchQuery: String?): RetrofitBucket {
        var query = searchQuery
        val retrofitBucket = RetrofitBucket()
        retrofitBucket.url = "$baseUrl$OCS_API_VERSION/apps/files_sharing/api/v1/sharees"
        val queryMap: MutableMap<String, String> = HashMap()
        if (query == null) {
            query = ""
        }
        queryMap["format"] = "json"
        queryMap["search"] = query
        queryMap["itemType"] = "call"
        retrofitBucket.queryMap = queryMap
        return retrofitBucket
    }

    fun getUrlForFilePreviewWithRemotePath(baseUrl: String, remotePath: String?, px: Int): String {
        return (
            baseUrl + "/index.php/core/preview.png?file=" +
                Uri.encode(remotePath, "UTF-8") +
                "&x=" + px + "&y=" + px + "&a=1&mode=cover&forceIcon=1"
            )
    }

    fun getUrlForFilePreviewWithFileId(baseUrl: String, fileId: String, px: Int): String {
        return (
            baseUrl + "/index.php/core/preview?fileId=" +
                fileId + "&x=" + px + "&y=" + px + "&a=1&mode=cover&forceIcon=1"
            )
    }

    fun getSharingUrl(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/files_sharing/api/v1/shares"
    }

    fun getRetrofitBucketForContactsSearchFor14(baseUrl: String, searchQuery: String?): RetrofitBucket {
        val retrofitBucket = getRetrofitBucketForContactsSearch(baseUrl, searchQuery)
        retrofitBucket.url = "$baseUrl$OCS_API_VERSION/core/autocomplete/get"
        retrofitBucket.queryMap?.put("itemId", "new")
        return retrofitBucket
    }

    @JvmStatic
    fun getUrlForCapabilities(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/cloud/capabilities"
    }

    @Throws(NoSupportedApiException::class)
    fun getCallApiVersion(capabilities: User, versions: IntArray): Int {
        return getConversationApiVersion(capabilities, versions)
    }

    @JvmStatic
    @Throws(NoSupportedApiException::class)
    @Suppress("ReturnCount")
    fun getConversationApiVersion(user: User, versions: IntArray): Int {
        var hasApiV4 = false
        for (version in versions) {
            hasApiV4 = hasApiV4 or (version == API_V4)
        }
        if (!hasApiV4) {
            val e = Exception("Api call did not try conversation-v4 api")
            Log.d(TAG, e.message, e)
        }
        for (version in versions) {
            if (user.hasSpreedFeatureCapability("conversation-v$version")) {
                return version
            }

            // Fallback for old API versions
            if (version == API_V1 || version == API_V2) {
                if (user.hasSpreedFeatureCapability("conversation-v2")) {
                    return version
                }
                if (version == API_V1 &&
                    user.hasSpreedFeatureCapability("mention-flag") &&
                    !user.hasSpreedFeatureCapability("conversation-v4")
                ) {
                    return version
                }
            }
        }
        throw NoSupportedApiException()
    }

    @JvmStatic
    @Throws(NoSupportedApiException::class)
    @Suppress("ReturnCount")
    fun getSignalingApiVersion(user: User, versions: IntArray): Int {
        val spreedCapabilities = user.capabilities!!.spreedCapability
        for (version in versions) {
            if (spreedCapabilities != null) {
                if (spreedCapabilities.features!!.contains("signaling-v$version")) {
                    return version
                }
                if (version == API_V2 &&
                    hasSpreedFeatureCapability(spreedCapabilities, SpreedFeatures.SIP_SUPPORT) &&
                    !hasSpreedFeatureCapability(spreedCapabilities, SpreedFeatures.SIGNALING_V3)
                ) {
                    return version
                }
                if (version == API_V1 &&
                    !hasSpreedFeatureCapability(spreedCapabilities, SpreedFeatures.SIGNALING_V3)
                ) {
                    // Has no capability, we just assume it is always there when there is no v3 or later
                    return version
                }
            }
        }
        throw NoSupportedApiException()
    }

    @JvmStatic
    @Throws(NoSupportedApiException::class)
    fun getChatApiVersion(spreedCapabilities: SpreedCapability, versions: IntArray): Int {
        for (version in versions) {
            if (version == API_V1 && hasSpreedFeatureCapability(spreedCapabilities, SpreedFeatures.CHAT_V2)) {
                // Do not question that chat-v2 capability shows the availability of api/v1/ endpoint *see no evil*
                return version
            }
        }
        throw NoSupportedApiException()
    }

    private fun getUrlForApi(version: Int, baseUrl: String?): String {
        return baseUrl + SPREED_API_BASE + version
    }

    fun getUrlForRooms(version: Int, baseUrl: String?): String {
        return getUrlForApi(version, baseUrl) + "/room"
    }

    @JvmStatic
    fun getUrlForRoom(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRooms(version, baseUrl) + "/" + token
    }

    fun getUrlForAttendees(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/attendees"
    }

    fun getUrlForParticipants(version: Int, baseUrl: String?, token: String?): String {
        if (token.isNullOrEmpty()) {
            Log.e(TAG, "token was null or empty")
        }
        return getUrlForRoom(version, baseUrl, token) + "/participants"
    }

    fun getUrlForParticipantsActive(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForParticipants(version, baseUrl, token) + "/active"
    }

    @JvmStatic
    fun getUrlForParticipantsSelf(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForParticipants(version, baseUrl, token) + "/self"
    }

    fun getUrlForParticipantsResendInvitations(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForParticipants(version, baseUrl, token) + "/resend-invitations"
    }

    fun getUrlForRoomFavorite(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/favorite"
    }

    fun getUrlForRoomModerators(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/moderators"
    }

    @JvmStatic
    fun getUrlForRoomNotificationLevel(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/notify"
    }

    fun getUrlForRoomPublic(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/public"
    }

    fun getUrlForRoomPassword(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/password"
    }

    fun getUrlForRoomReadOnlyState(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/read-only"
    }

    fun getUrlForRoomWebinaryLobby(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/webinar/lobby"
    }

    @JvmStatic
    fun getUrlForRoomNotificationCalls(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/notify-calls"
    }

    fun getUrlForCall(version: Int, baseUrl: String?, token: String): String {
        return getUrlForApi(version, baseUrl) + "/call/" + token
    }

    fun getUrlForChat(version: Int, baseUrl: String?, token: String): String {
        return getUrlForApi(version, baseUrl) + "/chat/" + token
    }

    @JvmStatic
    fun getUrlForMentionSuggestions(version: Int, baseUrl: String?, token: String): String {
        return getUrlForChat(version, baseUrl, token) + "/mentions"
    }

    fun getUrlForChatMessage(version: Int, baseUrl: String?, token: String, messageId: String): String {
        return getUrlForChat(version, baseUrl, token) + "/" + messageId
    }

    fun getUrlForChatSharedItems(version: Int, baseUrl: String?, token: String): String {
        return getUrlForChat(version, baseUrl, token) + "/share"
    }

    fun getUrlForChatSharedItemsOverview(version: Int, baseUrl: String?, token: String): String {
        return getUrlForChatSharedItems(version, baseUrl, token) + "/overview"
    }

    fun getUrlForSignaling(version: Int, baseUrl: String?): String {
        return getUrlForApi(version, baseUrl) + "/signaling"
    }

    @JvmStatic
    fun getUrlForSignalingBackend(version: Int, baseUrl: String?): String {
        return getUrlForSignaling(version, baseUrl) + "/backend"
    }

    @JvmStatic
    fun getUrlForSignalingSettings(version: Int, baseUrl: String?): String {
        return getUrlForSignaling(version, baseUrl) + "/settings"
    }

    fun getUrlForSignaling(version: Int, baseUrl: String?, token: String): String {
        return getUrlForSignaling(version, baseUrl) + "/" + token
    }

    fun getUrlForOpenConversations(version: Int, baseUrl: String?): String {
        return getUrlForApi(version, baseUrl) + "/listed-room"
    }

    @Suppress("LongParameterList")
    fun getRetrofitBucketForCreateRoom(
        version: Int,
        baseUrl: String?,
        roomType: String,
        source: String?,
        invite: String?,
        conversationName: String?
    ): RetrofitBucket {
        val retrofitBucket = RetrofitBucket()
        retrofitBucket.url = getUrlForRooms(version, baseUrl)
        val queryMap: MutableMap<String, String> = HashMap()
        queryMap["roomType"] = roomType
        if (invite != null) {
            queryMap["invite"] = invite
        }
        if (source != null) {
            queryMap["source"] = source
        }
        if (conversationName != null) {
            queryMap["roomName"] = conversationName
        }
        retrofitBucket.queryMap = queryMap
        return retrofitBucket
    }

    @JvmStatic
    fun getRetrofitBucketForAddParticipant(
        version: Int,
        baseUrl: String?,
        token: String?,
        user: String
    ): RetrofitBucket {
        val retrofitBucket = RetrofitBucket()
        retrofitBucket.url = getUrlForParticipants(version, baseUrl, token)
        val queryMap: MutableMap<String, String> = HashMap()
        queryMap["newParticipant"] = user
        retrofitBucket.queryMap = queryMap
        return retrofitBucket
    }

    @JvmStatic
    fun getRetrofitBucketForAddParticipantWithSource(
        version: Int,
        baseUrl: String?,
        token: String?,
        source: String,
        id: String
    ): RetrofitBucket {
        val retrofitBucket = getRetrofitBucketForAddParticipant(version, baseUrl, token, id)
        retrofitBucket.queryMap?.put("source", source)
        return retrofitBucket
    }

    fun getUrlForUserProfile(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/cloud/user"
    }

    fun getUrlForUserData(baseUrl: String, userId: String): String {
        return "$baseUrl$OCS_API_VERSION/cloud/users/$userId"
    }

    fun getUrlForUserSettings(baseUrl: String): String {
        // FIXME Introduce API version
        return "$baseUrl$OCS_API_VERSION$SPREED_API_VERSION/settings/user"
    }

    fun getUrlPostfixForStatus(): String {
        return "/status.php"
    }

    @JvmStatic
    fun getUrlForAvatar(baseUrl: String?, name: String?, requestBigSize: Boolean): String {
        val avatarSize = if (requestBigSize) AVATAR_SIZE_BIG else AVATAR_SIZE_SMALL
        return baseUrl + "/index.php/avatar/" + Uri.encode(name) + "/" + avatarSize
    }

    @JvmStatic
    fun getUrlForGuestAvatar(baseUrl: String?, name: String?, requestBigSize: Boolean): String {
        val avatarSize = if (requestBigSize) AVATAR_SIZE_BIG else AVATAR_SIZE_SMALL
        return baseUrl + "/index.php/avatar/guest/" + Uri.encode(name) + "/" + avatarSize
    }

    fun getUrlForConversationAvatar(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/avatar"
    }

    fun getUrlForConversationAvatarWithVersion(
        version: Int,
        baseUrl: String?,
        token: String?,
        isDark: Boolean,
        avatarVersion: String?
    ): String {
        var isDarkString = ""
        if (isDark) {
            isDarkString = "/dark"
        }
        var avatarVersionString = ""
        if (avatarVersion != null) {
            avatarVersionString = "?avatarVersion=$avatarVersion"
        }
        return getUrlForRoom(version, baseUrl, token) + "/avatar" + isDarkString + avatarVersionString
    }

    @JvmStatic
    fun getCredentials(username: String?, token: String?): String? {
        return if (TextUtils.isEmpty(username) && TextUtils.isEmpty(token)) {
            null
        } else {
            basic(username!!, token!!, StandardCharsets.UTF_8)
        }
    }

    @JvmStatic
    fun getUrlNextcloudPush(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/notifications/api/v2/push"
    }

    @JvmStatic
    fun getUrlPushProxy(): String {
        return sharedApplication!!.applicationContext.resources.getString(R.string.nc_push_server_url) + "/devices"
    }

    // see https://github.com/nextcloud/notifications/blob/master/docs/ocs-endpoint-v2.md
    fun getUrlForNcNotificationWithId(baseUrl: String, notificationId: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/notifications/api/v2/notifications/$notificationId"
    }

    fun getUrlForSearchByNumber(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/cloud/users/search/by-phone"
    }

    fun getUrlForFileUpload(baseUrl: String, user: String, remotePath: String): String {
        return "$baseUrl/remote.php/dav/files/$user$remotePath"
    }

    fun getUrlForChunkedUpload(baseUrl: String, user: String): String {
        return "$baseUrl/remote.php/dav/uploads/$user"
    }

    fun getUrlForFileDownload(baseUrl: String, user: String, remotePath: String): String {
        return "$baseUrl/remote.php/dav/files/$user/$remotePath"
    }

    fun getUrlForTempAvatar(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/spreed/temp-user-avatar"
    }

    fun getUrlForUserFields(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/cloud/user/fields"
    }

    fun getUrlToSendLocation(version: Int, baseUrl: String?, roomToken: String): String {
        return getUrlForChat(version, baseUrl, roomToken) + "/share"
    }

    fun getUrlForHoverCard(baseUrl: String, userId: String): String {
        return baseUrl + OCS_API_VERSION +
            "/hovercard/v1/" + userId
    }

    fun getUrlForChatReadMarker(version: Int, baseUrl: String?, roomToken: String): String {
        return getUrlForChat(version, baseUrl, roomToken) + "/read"
    }

    /*
     * OCS Status API
     */
    @JvmStatic
    fun getUrlForStatus(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/user_status/api/v1/user_status"
    }

    fun getUrlForSetStatusType(baseUrl: String): String {
        return getUrlForStatus(baseUrl) + "/status"
    }

    fun getUrlForPredefinedStatuses(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/user_status/api/v1/predefined_statuses"
    }

    fun getUrlForStatusMessage(baseUrl: String): String {
        return getUrlForStatus(baseUrl) + "/message"
    }

    fun getUrlForSetCustomStatus(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/user_status/api/v1/user_status/message/custom"
    }

    fun getUrlForSetPredefinedStatus(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/user_status/api/v1/user_status/message/predefined"
    }

    fun getUrlForUserStatuses(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/apps/user_status/api/v1/statuses"
    }

    fun getUrlForMessageReaction(baseUrl: String, roomToken: String, messageId: String): String {
        return "$baseUrl$OCS_API_VERSION$SPREED_API_VERSION/reaction/$roomToken/$messageId"
    }

    fun getUrlForUnifiedSearch(baseUrl: String, providerId: String): String {
        return "$baseUrl$OCS_API_VERSION/search/providers/$providerId/search"
    }

    fun getUrlForPoll(baseUrl: String, roomToken: String, pollId: String): String {
        return getUrlForPoll(baseUrl, roomToken) + "/" + pollId
    }

    fun getUrlForPoll(baseUrl: String, roomToken: String): String {
        return "$baseUrl$OCS_API_VERSION$SPREED_API_VERSION/poll/$roomToken"
    }

    @JvmStatic
    fun getUrlForMessageExpiration(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/message-expiration"
    }

    fun getUrlForOpenGraph(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/references/resolve"
    }

    fun getUrlForRecording(version: Int, baseUrl: String?, token: String): String {
        return getUrlForApi(version, baseUrl) + "/recording/" + token
    }

    fun getUrlForRequestAssistance(version: Int, baseUrl: String?, token: String): String {
        return getUrlForApi(version, baseUrl) + "/breakout-rooms/" + token + "/request-assistance"
    }

    fun getUrlForConversationDescription(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/description"
    }

    fun getUrlForTranslation(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/translation/translate"
    }

    fun getUrlForLanguages(baseUrl: String): String {
        return "$baseUrl$OCS_API_VERSION/translation/languages"
    }

    fun getUrlForReminder(user: User, roomToken: String, messageId: String, version: Int): String {
        val url = getUrlForChatMessage(version, user.baseUrl!!, roomToken, messageId)
        return "$url/reminder"
    }

    fun getUrlForRecordingConsent(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRoom(version, baseUrl, token) + "/recording-consent"
    }

    fun getUrlForInvitation(baseUrl: String): String {
        return baseUrl + OCS_API_VERSION + SPREED_API_VERSION + "/federation/invitation"
    }

    fun getUrlForInvitationAccept(baseUrl: String, id: Int): String {
        return getUrlForInvitation(baseUrl) + "/" + id
    }

    fun getUrlForInvitationReject(baseUrl: String, id: Int): String {
        return getUrlForInvitation(baseUrl) + "/" + id
    }

    @JvmStatic
    fun getUrlForRoomCapabilities(version: Int, baseUrl: String?, token: String?): String {
        return getUrlForRooms(version, baseUrl) + "/" + token + "/capabilities"
    }
}
