/*
 * Clover - 4chan browser
 * Copyright (C) 2014  Floens https://github.com/Floens/Clover/
 * Copyright (C) 2026  otacoo https://github.com/otacoo/Clover/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.otacoo.chan.utils;

import android.view.MotionEvent;

public class RotationGestureDetector {
    private static final int INVALID_POINTER_ID = -1;
    private float fX, fY, sX, sY;
    private int ptrID1, ptrID2;
    private float mAngle;

    private final OnRotationGestureListener mListener;

    public float getAngle() {
        return mAngle;
    }

    public RotationGestureDetector(OnRotationGestureListener listener) {
        mListener = listener;
        ptrID1 = INVALID_POINTER_ID;
        ptrID2 = INVALID_POINTER_ID;
    }

    public void onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ptrID1 = event.getPointerId(event.getActionIndex());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                ptrID2 = event.getPointerId(event.getActionIndex());
                sX = event.getX(event.findPointerIndex(ptrID1));
                sY = event.getY(event.findPointerIndex(ptrID1));
                fX = event.getX(event.findPointerIndex(ptrID2));
                fY = event.getY(event.findPointerIndex(ptrID2));
                break;
            case MotionEvent.ACTION_MOVE:
                if (ptrID1 != INVALID_POINTER_ID && ptrID2 != INVALID_POINTER_ID) {
                    int index1 = event.findPointerIndex(ptrID1);
                    int index2 = event.findPointerIndex(ptrID2);
                    if (index1 != -1 && index2 != -1) {
                        float nfX = event.getX(index2), nfY = event.getY(index2);
                        float nsX = event.getX(index1), nsY = event.getY(index1);

                        float prevAngle = (float) Math.toDegrees(Math.atan2(fY - sY, fX - sX));
                        float currAngle = (float) Math.toDegrees(Math.atan2(nfY - nsY, nfX - nsX));
                        mAngle = currAngle - prevAngle;
                        // Normalize to [-180, 180]
                        if (mAngle > 180) mAngle -= 360;
                        if (mAngle < -180) mAngle += 360;

                        fX = nfX; fY = nfY; sX = nsX; sY = nsY;

                        if (mListener != null) {
                            mListener.onRotation(this);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                ptrID1 = INVALID_POINTER_ID;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                ptrID2 = INVALID_POINTER_ID;
                break;
            case MotionEvent.ACTION_CANCEL:
                ptrID1 = INVALID_POINTER_ID;
                ptrID2 = INVALID_POINTER_ID;
                break;
        }
    }

    public void recenterAngles(MotionEvent event) {
        int idx1 = event.findPointerIndex(ptrID1);
        int idx2 = event.findPointerIndex(ptrID2);
        if (idx1 != -1 && idx2 != -1) {
            sX = event.getX(idx1); sY = event.getY(idx1);
            fX = event.getX(idx2); fY = event.getY(idx2);
        }
    }

    public interface OnRotationGestureListener {
        void onRotation(RotationGestureDetector rotationDetector);
    }
}
