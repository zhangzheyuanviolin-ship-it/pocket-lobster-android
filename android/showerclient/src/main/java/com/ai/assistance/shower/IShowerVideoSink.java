package com.ai.assistance.shower;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IShowerVideoSink extends IInterface {

    void onVideoFrame(byte[] data) throws RemoteException;

    abstract class Stub extends Binder implements IShowerVideoSink {

        private static final String DESCRIPTOR = "com.ai.assistance.shower.IShowerVideoSink";

        static final int TRANSACTION_onVideoFrame = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_frameChunk = IBinder.FIRST_CALL_TRANSACTION + 1;
        private java.io.ByteArrayOutputStream chunks;
        private long chunkId;
        private int chunkOffset;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IShowerVideoSink asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IShowerVideoSink) {
                return (IShowerVideoSink) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_frameChunk: {
                    data.enforceInterface(DESCRIPTOR);
                    long id = data.readLong();
                    int offset = data.readInt();
                    int total = data.readInt();
                    byte[] bytes = data.createByteArray();
                    synchronized (this) {
                        if (total <= 0 || total > 8 * 1024 * 1024 || bytes == null ||
                                bytes.length > 65536 || offset < 0 || offset + bytes.length > total) {
                            chunks = null;
                            throw new IllegalArgumentException("Invalid video packet");
                        }
                        if (offset == 0) {
                            chunks = new java.io.ByteArrayOutputStream(total);
                            chunkId = id;
                            chunkOffset = 0;
                        }
                        if (chunks == null || chunkId != id || chunkOffset != offset) {
                            chunks = null;
                            throw new IllegalStateException("Out-of-order video packet");
                        }
                        chunks.write(bytes, 0, bytes.length);
                        chunkOffset += bytes.length;
                        if (chunkOffset == total) {
                            byte[] complete = chunks.toByteArray();
                            chunks = null;
                            onVideoFrame(complete);
                        }
                    }
                    reply.writeNoException();
                    return true;
                }
                case INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                case TRANSACTION_onVideoFrame: {
                    data.enforceInterface(DESCRIPTOR);
                    byte[] frame = data.createByteArray();
                    onVideoFrame(frame);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static final class Proxy implements IShowerVideoSink {

            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void onVideoFrame(byte[] data) throws RemoteException {
                if (data != null && data.length > 65536) {
                    long id = System.nanoTime();
                    for (int offset = 0; offset < data.length; offset += 65536) {
                        Parcel part = Parcel.obtain();
                        Parcel reply = Parcel.obtain();
                        try {
                            part.writeInterfaceToken(DESCRIPTOR);
                            part.writeLong(id);
                            part.writeInt(offset);
                            part.writeInt(data.length);
                            part.writeByteArray(java.util.Arrays.copyOfRange(data, offset, Math.min(offset + 65536, data.length)));
                            if (!remote.transact(TRANSACTION_frameChunk, part, reply, 0)) {
                                throw new RemoteException("Video chunk transport unavailable");
                            }
                            reply.readException();
                        } finally { reply.recycle(); part.recycle(); }
                    }
                    return;
                }
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeByteArray(data);
                    remote.transact(TRANSACTION_onVideoFrame, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
