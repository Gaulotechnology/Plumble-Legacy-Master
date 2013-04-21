package com.morlunk.mumbleclient.app;

import java.util.List;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.IBinder;

import com.morlunk.mumbleclient.service.MumbleService;
import com.morlunk.mumbleclient.service.MumbleService.LocalBinder;
import com.morlunk.mumbleclient.service.model.Channel;

public class ChannelSearchProvider extends ContentProvider {

	ServiceConnection conn = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mService = ((LocalBinder) service).getService();
		}

		@SuppressLint("NewApi")
		@Override
		public void onServiceDisconnected(ComponentName name) {
			mService = null;
		}
	};
	
	private MumbleService mService;
	
	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean onCreate() {
		return true;
	}
	

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {	
		
		// Try to connect to the service. Although it's asynchronous, it's pretty fast and the user shouldn't notice any discarded queries.
		if(mService == null) {
			Intent serviceIntent = new Intent(getContext(), MumbleService.class);
			getContext().bindService(serviceIntent, conn, 0);
			return null;
		}
		
		String query = "";
		for(int x=0;x<selectionArgs.length;x++) {
			query += selectionArgs[x];
			if(x != selectionArgs.length-1)
				query += " ";
		}
		
		query = query.toLowerCase();
		
		MatrixCursor cursor = new MatrixCursor(new String[] { "_ID", SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_INTENT_DATA });
		
		List<Channel> channels = mService.getChannelList();
		
		for(int x=0;x<channels.size();x++) {
			Channel channel = channels.get(x);
			String channelNameLower = channel.name.toLowerCase();
			if(channelNameLower.contains(query)) {
				cursor.addRow(new Object[] { x, channel.name, channel.id });
			}
		}
		return cursor;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		// TODO Auto-generated method stub
		return 0;
	}

}
