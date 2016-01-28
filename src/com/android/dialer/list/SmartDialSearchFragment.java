/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dialer.list;

import static android.Manifest.permission.CALL_PHONE;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.Html;

import android.view.View;
import android.widget.TextView;
import com.android.contacts.common.list.ContactEntryListAdapter;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.dialer.DialtactsActivity;
import com.android.dialer.dialpad.SmartDialCursorLoader;
import com.android.dialer.R;
import com.android.dialer.widget.EmptyContentView;
import com.android.internal.telephony.TelephonyIntents;
import com.android.phone.common.incall.CallMethodInfo;
import com.android.phone.common.incall.CallMethodHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Implements a fragment to load and display SmartDial search results.
 */
public class SmartDialSearchFragment extends SearchFragment
        implements EmptyContentView.OnEmptyViewActionButtonClickedListener,
        DialerPhoneNumberListAdapter.searchMethodClicked {
    private static final String TAG = SmartDialSearchFragment.class.getSimpleName();

    private static final int CALL_PHONE_PERMISSION_REQUEST_CODE = 1;

    private HashMap<ComponentName, CallMethodInfo> mAvailableProviders;

    /**
     * Creates a SmartDialListAdapter to display and operate on search results.
     */
    @Override
    protected ContactEntryListAdapter createListAdapter() {
        SmartDialNumberListAdapter adapter = new SmartDialNumberListAdapter(getActivity());
        adapter.setUseCallableUri(super.usesCallableUri());
        adapter.setQuickContactEnabled(true);
        // Set adapter's query string to restore previous instance state.
        adapter.setQueryString(getQueryString());
        adapter.setSearchListner(this);

        return adapter;
    }

    /**
     * Creates a SmartDialCursorLoader object to load query results.
     */
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // Smart dialing does not support Directory Load, falls back to normal search instead.
        if (id == getDirectoryLoaderId()) {
            return super.onCreateLoader(id, args);
        } else {
            return updateData();
        }
    }

    /**
     * Gets the Phone Uri of an entry for calling.
     * @param position Location of the data of interest.
     * @return Phone Uri to establish a phone call.
     */
    @Override
    protected Uri getPhoneUri(int position) {
        final SmartDialNumberListAdapter adapter = (SmartDialNumberListAdapter) getAdapter();
        return adapter.getDataUri(position);
    }

    private Loader<Cursor> updateData() {
        final SmartDialNumberListAdapter adapter = (SmartDialNumberListAdapter) getAdapter();

        SmartDialCursorLoader loader = new SmartDialCursorLoader(super.getContext());

        if (mCurrentCallMethodInfo != null) {
            adapter.configureLoader(loader, mCurrentCallMethodInfo.mMimeType);
        } else {
            adapter.configureLoader(loader, null);
        }

        return loader;
    }

    @Override
    public void setupEmptyView() {
        final SmartDialNumberListAdapter adapter = (SmartDialNumberListAdapter) getAdapter();

        if (mCurrentCallMethodInfo == null) {
            mCurrentCallMethodInfo = ((DialtactsActivity) getActivity()).getCurrentCallMethod();
        }

        if (mEmptyView != null && getActivity() != null) {
            if (!PermissionsUtil.hasPermission(getActivity(), CALL_PHONE)) {
                mEmptyView.setImage(R.drawable.empty_contacts);
                mEmptyView.setActionLabel(R.string.permission_single_turn_on);
                mEmptyView.setDescription(R.string.permission_place_call);
                mEmptyView.setActionClickedListener(this);
            } else if (adapter.getCount() == 0) {
                mEmptyView.setActionLabel(mEmptyView.NO_LABEL);
                mEmptyView.setImage(R.drawable.empty_contacts);
                Resources r = getResources();

                // Get Current InCall plugin specific call methods, we don't want to update this
                // suddenly so just the currently available ones are fine.
                //setAvailableProviders(CallMethodHelper.getAllCallMethods());
                if (mAvailableProviders == null) {
                    mAvailableProviders = new HashMap<ComponentName, CallMethodInfo>();
                }

                if (mCurrentCallMethodInfo != null && mCurrentCallMethodInfo.mIsInCallProvider) {
                    showProviderHint(r);
                } else {
                    showSuggestion(r);
                }
            }
        }
    }

    public void showNormalT9Hint(Resources r) {
        mEmptyView.setImage(R.drawable.empty_contacts);
        mEmptyView.setDescription(
                String.format(r.getString(R.string.empty_dialpad_t9_example),
                        r.getString(R.string.empty_dialpad_example_name)));

        int[] idsToFormat = new int[] {
                R.id.empty_dialpad_pqrs,
                R.id.empty_dialpad_abc,
                R.id.empty_dialpad_mno
        };
        for (int id : idsToFormat) {
            TextView textView = (TextView) mEmptyView.findViewById(id);
            textView.setText(Html.fromHtml(textView.getText().toString()));
            textView.setVisibility(View.VISIBLE);
        }
        mEmptyView.setSubViewVisibility(View.VISIBLE);
    }

    public void showProviderHint(Resources r) {
        String text;
        if (!mCurrentCallMethodInfo.mIsAuthenticated) {
            // Sign into current selected call method to make calls
            text = getString(R.string.sign_in_hint_text, mCurrentCallMethodInfo.mName);
        } else {
            // InCallApi provider specified hint
            text = mCurrentCallMethodInfo.mT9HintDescription;
        }
        Drawable heroImage = mCurrentCallMethodInfo.mSingleColorBrandIcon;
        heroImage.setTint(r.getColor(R.color.hint_image_color));
        mEmptyView.setImage(heroImage);
        mEmptyView.setDescription(text);
        mEmptyView.setSubViewVisibility(View.GONE);
        // TODO: put action button for login in or switching provider!
    }

    public void showSuggestion(Resources r) {
        ConnectivityManager connManager =
                (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        CallMethodInfo emergencyOnlyCallMethod = CallMethodInfo.getEmergencyCallMethod(getContext());
        if ((mCurrentCallMethodInfo == null /*&& (mSims == null || mSims.isEmpty())*/) ||
                (mCurrentCallMethodInfo != null && mCurrentCallMethodInfo.equals(emergencyOnlyCallMethod))) {
            // If no sims available and emergency only call method selected,
            // alert user that only emergency calls are allowed for the current call method.
            String text = r.getString(R.string.emergency_call_hint_text);
            Drawable heroImage = r.getDrawable(R.drawable.ic_nosim);
            heroImage.setTint(r.getColor(R.color.emergency_call_icon_color));

            mEmptyView.setImage(heroImage);
            mEmptyView.setDescription(text);
            mEmptyView.setSubViewVisibility(View.GONE);
        } else if (mCurrentCallMethodInfo != null && !mCurrentCallMethodInfo.mIsInCallProvider &&
                !mAvailableProviders.isEmpty() && mWifi.isConnected()) {
            String template;
            Drawable heroImage;
            String text;
            TelephonyManager tm =
                    (TelephonyManager) getActivity().getSystemService(Context.TELEPHONY_SERVICE);

            if (tm.isNetworkRoaming(mCurrentCallMethodInfo.mSubId)) {
                heroImage = r.getDrawable(R.drawable.ic_roaming);
                template = r.getString(R.string.roaming_hint_text);
                text = String.format(template, mCurrentCallMethodInfo.mName, hintTextRequest());
            } else {
                heroImage = r.getDrawable(R.drawable.ic_signal_wifi_3_bar);
                template = r.getString(R.string.wifi_hint_text);
                text = String.format(template, hintTextRequest());
            }
            mEmptyView.setImage(heroImage);
            mEmptyView.setDescription(text);
            mEmptyView.setSubViewVisibility(View.GONE);
        } else {
            showNormalT9Hint(r);
        }
    }

    private String hintTextRequest() {
        // Randomly choose an item that is not a sim to prompt user to switch to
        List<CallMethodInfo> valuesList =
                new ArrayList<CallMethodInfo>(mAvailableProviders.values());

        int randomIndex = new Random().nextInt(valuesList.size());
        return valuesList.get(randomIndex).mName;
    }

    @Override
    public void setCurrentCallMethod(CallMethodInfo cmi) {
        super.setCurrentCallMethod(cmi);
        reloadData();
    }

    public void setAvailableProviders(HashMap<ComponentName, CallMethodInfo> callMethods) {
        if (mAvailableProviders != null) {
            mAvailableProviders.clear();
        } else {
            mAvailableProviders = new HashMap<ComponentName, CallMethodInfo>();
        }
        // Note: these should be available (enabled) providers only!
        mAvailableProviders.putAll(callMethods);
        setupEmptyView();
    }

    @Override
    public void onEmptyViewActionButtonClicked() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        requestPermissions(new String[]{CALL_PHONE}, CALL_PHONE_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == CALL_PHONE_PERMISSION_REQUEST_CODE) {
            setupEmptyView();
        }
    }

    public boolean isShowingPermissionRequest() {
        return mEmptyView != null && mEmptyView.isShowingContent();
    }
}
