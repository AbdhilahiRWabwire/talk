/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
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

package com.nextcloud.talk.controllers;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.ImageView;

import com.bluelinelabs.conductor.RouterTransaction;
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;
import com.nextcloud.talk.R;
import com.nextcloud.talk.activities.CallActivity;
import com.nextcloud.talk.adapters.messages.MagicIncomingTextMessageViewHolder;
import com.nextcloud.talk.adapters.messages.MagicOutcomingTextMessageViewHolder;
import com.nextcloud.talk.api.NcApi;
import com.nextcloud.talk.application.NextcloudTalkApplication;
import com.nextcloud.talk.callbacks.MentionAutocompleteCallback;
import com.nextcloud.talk.controllers.base.BaseController;
import com.nextcloud.talk.models.database.UserEntity;
import com.nextcloud.talk.models.json.call.Call;
import com.nextcloud.talk.models.json.call.CallOverall;
import com.nextcloud.talk.models.json.chat.ChatMessage;
import com.nextcloud.talk.models.json.chat.ChatOverall;
import com.nextcloud.talk.models.json.generic.GenericOverall;
import com.nextcloud.talk.models.json.mention.Mention;
import com.nextcloud.talk.presenters.MentionAutocompletePresenter;
import com.nextcloud.talk.utils.ApiUtils;
import com.nextcloud.talk.utils.KeyboardUtils;
import com.nextcloud.talk.utils.bundle.BundleKeys;
import com.nextcloud.talk.utils.database.user.UserUtils;
import com.nextcloud.talk.utils.glide.GlideApp;
import com.otaliastudios.autocomplete.Autocomplete;
import com.otaliastudios.autocomplete.AutocompleteCallback;
import com.otaliastudios.autocomplete.AutocompletePresenter;
import com.otaliastudios.autocomplete.CharPolicy;
import com.stfalcon.chatkit.commons.ImageLoader;
import com.stfalcon.chatkit.commons.models.IMessage;
import com.stfalcon.chatkit.messages.MessageInput;
import com.stfalcon.chatkit.messages.MessagesList;
import com.stfalcon.chatkit.messages.MessagesListAdapter;
import com.stfalcon.chatkit.utils.DateFormatter;
import com.webianks.library.PopupBubble;

import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import autodagger.AutoInjector;
import butterknife.BindView;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Response;

@AutoInjector(NextcloudTalkApplication.class)
public class ChatController extends BaseController implements MessagesListAdapter.OnLoadMoreListener,
        MessagesListAdapter.Formatter<Date>, MessagesListAdapter.OnMessageLongClickListener{
    private static final String TAG = "ChatController";

    @Inject
    NcApi ncApi;
    @Inject
    UserUtils userUtils;

    @BindView(R.id.input)
    MessageInput messageInput;
    @BindView(R.id.messagesList)
    MessagesList messagesList;
    @BindView(R.id.popupBubble)
    PopupBubble popupBubble;
    private List<Disposable> disposableList = new ArrayList<>();
    private String conversationName;
    private String roomToken;
    private UserEntity currentUser;
    private String roomPassword;
    private Call currentCall;
    private boolean inChat = false;
    private boolean historyRead = false;
    private int globalLastKnownFutureMessageId = -1;
    private int globalLastKnownPastMessageId = -1;
    private MessagesListAdapter<ChatMessage> adapter;
    private Menu globalMenu;

    private Autocomplete mentionAutocomplete;
    private LinearLayoutManager layoutManager;

    private int newMessagesCount = 0;

    /*
    TODO:
        - check push notifications
        - new conversation handling
     */
    public ChatController(Bundle args) {
        super(args);
        setHasOptionsMenu(true);
        this.conversationName = args.getString(BundleKeys.KEY_CONVERSATION_NAME);
        this.currentUser = Parcels.unwrap(args.getParcelable(BundleKeys.KEY_USER_ENTITY));
        this.roomToken = args.getString(BundleKeys.KEY_ROOM_TOKEN);
        this.roomPassword = args.getString(BundleKeys.KEY_ROOM_PASSWORD, "");
    }

    @Override
    protected View inflateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
        return inflater.inflate(R.layout.controller_chat, container, false);
    }

    @Override
    protected void onViewBound(@NonNull View view) {
        super.onViewBound(view);
        NextcloudTalkApplication.getSharedApplication().getComponentApplication().inject(this);

        boolean adapterWasNull = false;

        if (adapter == null) {

            adapterWasNull = true;

            MessagesListAdapter.HoldersConfig holdersConfig = new MessagesListAdapter.HoldersConfig();
            holdersConfig.setIncoming(MagicIncomingTextMessageViewHolder.class,
                    R.layout.item_custom_incoming_text_message);
            holdersConfig.setOutcoming(MagicOutcomingTextMessageViewHolder.class,
                    R.layout.item_custom_outcoming_text_message);

            adapter = new MessagesListAdapter<>(currentUser.getUserId(), holdersConfig, new ImageLoader() {
                @Override
                public void loadImage(ImageView imageView, String url) {
                    GlideApp.with(NextcloudTalkApplication.getSharedApplication().getApplicationContext())
                            .asBitmap()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .load(url)
                            .centerInside()
                            .override(imageView.getMeasuredWidth(), imageView.getMeasuredHeight())
                            .apply(RequestOptions.bitmapTransform(new CircleCrop()))
                            .into(imageView);
                }
            });
        }


        messagesList.setAdapter(adapter);
        adapter.setLoadMoreListener(this);
        adapter.setDateHeadersFormatter(this::format);
        adapter.setOnMessageLongClickListener(this);

        layoutManager = (LinearLayoutManager) messagesList.getLayoutManager();

        popupBubble.setRecyclerView(messagesList);

        popupBubble.setPopupBubbleListener(context -> {
            if (newMessagesCount != 0) {
                new Handler().postDelayed(() -> messagesList.smoothScrollToPosition(newMessagesCount - 1), 200);
            }
        });

        messagesList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                if (newState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    if (newMessagesCount != 0) {
                        if (layoutManager.findFirstCompletelyVisibleItemPosition() < newMessagesCount) {
                            newMessagesCount = 0;

                            if (popupBubble.isShown()) {
                                popupBubble.hide();
                            }
                        }
                    }
                }
            }
        });

        setupMentionAutocomplete();

        messageInput.getInputEditText().setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        messageInput.setInputListener(input -> {
            sendMessage(input.toString());
            return true;
        });

        if (adapterWasNull) {
            joinRoomWithPassword();
        }
    }

    private void setupMentionAutocomplete() {
        float elevation = 6f;
        Drawable backgroundDrawable = new ColorDrawable(Color.WHITE);
        AutocompletePresenter<Mention> presenter = new MentionAutocompletePresenter(getApplicationContext(), roomToken);
        AutocompleteCallback<Mention> callback = new MentionAutocompleteCallback();

        mentionAutocomplete = Autocomplete.<Mention>on(messageInput.getInputEditText())
                .with(elevation)
                .with(backgroundDrawable)
                .with(new CharPolicy('@'))
                .with(presenter)
                .with(callback)
                .build();
    }

    @Override
    protected void onAttach(@NonNull View view) {
        super.onAttach(view);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (mentionAutocomplete != null && mentionAutocomplete.isPopupShowing()) {
            mentionAutocomplete.dismissPopup();
        }

        if (getActivity() != null) {
            new KeyboardUtils(getActivity(), getView());
        }
    }

    @Override
    protected String getTitle() {
        return conversationName;
    }

    @Override
    public boolean handleBack() {
        if (getRouter().hasRootController()) {
            getRouter().popToRoot(new HorizontalChangeHandler());
        } else {
            getRouter().setRoot(RouterTransaction.with(new MagicBottomNavigationController())
                    .pushChangeHandler(new HorizontalChangeHandler())
                    .popChangeHandler(new HorizontalChangeHandler()));
        }

        return true;
    }

    @Override
    public void onDestroy() {
        inChat = false;
        dispose();
        super.onDestroy();
    }

    private void dispose() {
        Disposable disposable;
        for (int i = 0; i < disposableList.size(); i++) {
            if ((disposable = disposableList.get(i)).isDisposed()) {
                disposable.dispose();
            }
        }
    }

    private void joinRoomWithPassword() {
        String password = "";

        if (TextUtils.isEmpty(roomPassword)) {
            password = roomPassword;
        }

        ncApi.joinRoom(ApiUtils.getCredentials(currentUser.getUserId(), currentUser.getToken()), ApiUtils
                .getUrlForRoomParticipants(currentUser.getBaseUrl(), roomToken), password)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(3)
                .subscribe(new Observer<CallOverall>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        disposableList.add(d);
                    }

                    @Override
                    public void onNext(CallOverall callOverall) {
                        inChat = true;
                        pullChatMessages(0);
                        currentCall = callOverall.getOcs().getData();
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void sendMessage(String message) {
        Map<String, String> fieldMap = new HashMap<>();
        fieldMap.put("message", message);
        fieldMap.put("actorDisplayName", currentUser.getDisplayName());


        ncApi.sendChatMessage(ApiUtils.getCredentials(currentUser.getUserId(), currentUser.getToken()),
                ApiUtils.getUrlForChat(currentUser.getBaseUrl(), roomToken), fieldMap)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(3, observable -> inChat)
                .subscribe(new Observer<GenericOverall>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(GenericOverall genericOverall) {
                        if (popupBubble.isShown()) {
                            popupBubble.hide();
                        }

                        messagesList.smoothScrollToPosition(0);
                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void pullChatMessages(int lookIntoFuture) {
        Map<String, Integer> fieldMap = new HashMap<>();
        fieldMap.put("lookIntoFuture", lookIntoFuture);
        fieldMap.put("limit", 25);

        int lastKnown;
        if (lookIntoFuture == 1) {
            lastKnown = globalLastKnownFutureMessageId;
        } else {
            lastKnown = globalLastKnownPastMessageId;
        }

        if (lastKnown != -1) {
            fieldMap.put("lastKnownMessageId", lastKnown);
        }

        if (lookIntoFuture == 1) {
            ncApi.pullChatMessages(ApiUtils.getCredentials(currentUser.getUserId(), currentUser.getToken()),
                    ApiUtils.getUrlForChat(currentUser.getBaseUrl(), roomToken), fieldMap)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .takeWhile(observable -> inChat)
                    .retry(3, observable -> inChat)
                    .subscribe(new Observer<Response>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            disposableList.add(d);
                        }

                        @Override
                        public void onNext(Response response) {
                            processMessages(response, true);
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onComplete() {
                            pullChatMessages(1);
                        }
                    });

        } else {
            ncApi.pullChatMessages(ApiUtils.getCredentials(currentUser.getUserId(), currentUser.getToken()),
                    ApiUtils.getUrlForChat(currentUser.getBaseUrl(), roomToken), fieldMap)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(3, observable -> inChat)
                    .subscribe(new Observer<Response>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            disposableList.add(d);
                        }

                        @Override
                        public void onNext(Response response) {
                            processMessages(response, false);
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }
    }

    private void processMessages(Response response, boolean isFromTheFuture) {
        if (response.code() == 200) {
            boolean shouldForceFuture = false;
            if (globalLastKnownFutureMessageId == -1) {
                shouldForceFuture = true;
            }

            ChatOverall chatOverall = (ChatOverall) response.body();
            List<ChatMessage> chatMessageList = chatOverall.getOcs().getData();

            if (!isFromTheFuture) {
                for (int i = 0; i < chatMessageList.size(); i++) {
                    chatMessageList.get(i).setBaseUrl(currentUser.getBaseUrl());
                    if (globalLastKnownPastMessageId == -1 || chatMessageList.get(i).getJsonMessageId() <
                            globalLastKnownPastMessageId) {
                        globalLastKnownPastMessageId = chatMessageList.get(i).getJsonMessageId();
                    }

                    if (shouldForceFuture) {
                        if (chatMessageList.get(i).getJsonMessageId() > globalLastKnownFutureMessageId) {
                            globalLastKnownFutureMessageId = chatMessageList.get(i).getJsonMessageId();
                        }
                    }
                }

                adapter.addToEnd(chatMessageList, false);

            } else {
                for (int i = 0; i < chatMessageList.size(); i++) {
                    chatMessageList.get(i).setBaseUrl(currentUser.getBaseUrl());
                    boolean shouldScroll = layoutManager.findFirstVisibleItemPosition() == 0;

                    if (!shouldScroll) {
                        if (!popupBubble.isShown()) {
                            newMessagesCount = 1;
                            popupBubble.show();
                        } else if (popupBubble.isShown()) {
                            newMessagesCount++;
                        }
                    } else {
                        newMessagesCount = 0;
                    }

                    adapter.addToStart(chatMessageList.get(i), shouldScroll);
                }

                globalLastKnownFutureMessageId = Integer.parseInt(response.headers().get("X-Chat-Last-Given"));
            }

            if (shouldForceFuture) {
                pullChatMessages(1);
            }
        } else if (response.code() == 304 && !isFromTheFuture) {
            historyRead = true;
        }
    }

    @Override
    public void onLoadMore(int page, int totalItemsCount) {
        if (!historyRead) {
            pullChatMessages(0);
        }
    }


    @Override
    public String format(Date date) {
        if (DateFormatter.isToday(date)) {
            return getResources().getString(R.string.nc_date_header_today);
        } else if (DateFormatter.isYesterday(date)) {
            return getResources().getString(R.string.nc_date_header_yesterday);
        } else {
            return DateFormatter.format(date, DateFormatter.Template.STRING_DAY_MONTH_YEAR);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_conversation, menu);
        globalMenu = menu;
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                inChat = false;
                if (getRouter().hasRootController()) {
                    getRouter().popToRoot(new HorizontalChangeHandler());
                } else {
                    getRouter().setRoot(RouterTransaction.with(new MagicBottomNavigationController())
                            .pushChangeHandler(new HorizontalChangeHandler())
                            .popChangeHandler(new HorizontalChangeHandler()));
                }
                return true;

            case R.id.conversation_video_call:
                startActivity(getIntentForCall(false));
                return true;
            case R.id.conversation_voice_call:
                startActivity(getIntentForCall(true));
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private Intent getIntentForCall(boolean isVoiceOnlyCall) {
        Bundle bundle = new Bundle();
        bundle.putString(BundleKeys.KEY_ROOM_TOKEN, roomToken);
        bundle.putParcelable(BundleKeys.KEY_USER_ENTITY, Parcels.wrap(currentUser));
        bundle.putString(BundleKeys.KEY_CALL_SESSION, currentCall.getSessionId());

        if (isVoiceOnlyCall) {
            bundle.putBoolean(BundleKeys.KEY_CALL_VOICE_ONLY, true);
        }

        Intent callIntent = new Intent(getActivity(), CallActivity.class);
        callIntent.putExtras(bundle);

        return callIntent;
    }


    @Override
    public void onMessageLongClick(IMessage message) {
        if (getActivity() != null) {
            ClipboardManager clipboardManager = (android.content.ClipboardManager)
                    getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clipData = android.content.ClipData.newPlainText(
                    getResources().getString(R.string.nc_app_name), message.getText());
            if (clipboardManager != null) {
                clipboardManager.setPrimaryClip(clipData);
            }
        }
    }
}
