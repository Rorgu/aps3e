/*
 * Copyright 2013 Dolphin Emulator Project
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package org.vita3k.emulator.overlay;

//import org.libsdl.app.SDL;
//import org.libsdl.app.SDLActivity;
//import org.vita3k.emulator.Emulator;
import aenu.aps3e.R;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnTouchListener;

import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import aenu.aps3e.*;

/**
 * Draws the interactive input overlay on top of the
 * {@link SurfaceView} that is rendering emulation.
 */
public final class InputOverlay extends SurfaceView implements OnTouchListener
{
  // mirror what is in controller_dialog.cpp
  public final static int OVERLAY_MASK_BASIC = 1;
  //public final static int OVERLAY_MASK_L2R2 = 2;
  public final static int OVERLAY_MASK_TOUCH_SCREEN_SWITCH = 4;

  // wait 10 seconds without inputs before hiding
  private final static int OVERLAY_TIME_BEFORE_HIDE = 10;

  private final Set<InputOverlayDrawableButton> overlayButtons = new HashSet<>();
  private final Set<InputOverlayDrawableDpad> overlayDpads = new HashSet<>();
  private final Set<InputOverlayDrawableJoystick> overlayJoysticks = new HashSet<>();

  private Rect mSurfacePosition = null;

  private final int mOverlayMask = 1;
  private boolean mIsInEditMode = false;
  private InputOverlayDrawableButton mButtonBeingConfigured;
  private InputOverlayDrawableDpad mDpadBeingConfigured;
  private InputOverlayDrawableJoystick mJoystickBeingConfigured;
  //private static float mGlobalScale = 1.0f;
  private static float m_dpad_scale = 1.0f;
  private static float m_joystick_scale = 1.0f;
  private static float m_abxy_scale = 1.0f;
  private static float m_lr_scale = 1.0f;
  private static float m_ss_scale = 1.0f;
  private static float m_ps_scale = 1.0f;
  private static int mGlobalOpacity = 100;
  private static int m_dynamic_joystick = -1;
  private static boolean m_dynamic_joystick_pressed = false;

  private static boolean m_left_joystick_enabled = true;
  private static boolean m_right_joystick_enabled = true;
  private static boolean m_dpad_enabled = true;
  private static boolean m_square_enabled = true;
  private static boolean m_triangle_enabled = true;
  private static boolean m_circle_enabled = true;
  private static boolean m_cross_enabled = true;
  private static boolean m_start_enabled = true;
  private static boolean m_select_enabled = true;
  private static boolean m_l1_enabled = true;
  private static boolean m_l2_enabled = true;
  private static boolean m_l3_enabled = true;
  private static boolean m_r1_enabled = true;
  private static boolean m_r2_enabled = true;
  private static boolean m_r3_enabled = true;
  private static boolean m_ps_enabled = true;

  private Timer mTimer;

  // last Time the screen was touched
  private long mlastTouchTime;
  // is the overlay hidden because we didn't used it for long enough ?
  private boolean mShowingOverlay = true;

  private final SharedPreferences mPreferences;

  /**
   * Resizes a {@link Bitmap} by a given scale factor
   *
   * @param context The current {@link Context}
   * @param bitmap  The {@link Bitmap} to scale.
   * @param scale   The scale factor for the bitmap.
   * @return The scaled {@link Bitmap}
   */
  public static Bitmap resizeBitmap(Context context, Bitmap bitmap, float scale)
  {
    // Determine the button size based on the smaller screen dimension.
    // This makes sure the buttons are the same size in both portrait and landscape.
    DisplayMetrics dm = context.getResources().getDisplayMetrics();
    int minDimension = Math.min(dm.widthPixels, dm.heightPixels);

    return Bitmap.createScaledBitmap(bitmap,
            (int) (minDimension * scale),
            (int) (minDimension * scale),
            true);
  }

  /**
   * Constructor
   *
   * @param context The current {@link Context}.
   * @param attrs   {@link AttributeSet} for parsing XML attributes.
   */
  public InputOverlay(Context context, AttributeSet attrs)
  {
    super(context, attrs);

    mPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
    if (!mPreferences.getBoolean("OverlayInit", false))
      defaultOverlay();

    // Set the on touch listener.
    // Do not register the overlay as a touch listener
    // Instead let EmuSurface forward touch events
     setOnTouchListener(this);

    // Force draw
    setWillNotDraw(false);

    // Request focus for the overlay so it has priority on presses.
    requestFocus();

    SharedPreferences.Editor sPrefsEditor = mPreferences.edit();
    sPrefsEditor.putBoolean("OverlayInit", true);
    sPrefsEditor.apply();
    refreshControls();

    mTimer = new Timer();

    // call tick every second to check if we should stop displaying the overlay
    mTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        /*Emulator emu = (Emulator) SDL.getContext();
        emu.getmOverlay().*/tick();
      }
    }, 1000, 1000);

    resetHideTimer();
  }

  private void resetHideTimer(){
    if(!mShowingOverlay)
      invalidate();

    mShowingOverlay = true;
    mlastTouchTime = System.currentTimeMillis();
  }

  public void tick(){
    if(mOverlayMask == 0 || !mShowingOverlay || isInEditMode())
      return;

    long current_time = System.currentTimeMillis();
    if(current_time - mlastTouchTime >= OVERLAY_TIME_BEFORE_HIDE * 1000){
      mShowingOverlay = false;
      invalidate();
    }
  }

  public void setState(int overlay_mask){
    boolean was_showing = mOverlayMask != 0;
    if(mOverlayMask != overlay_mask){
      //mOverlayMask = overlay_mask;
      invalidate();
    }

    resetHideTimer();

    boolean is_showing = overlay_mask != 0;
    if(is_showing == was_showing)
      return;

    /*if(is_showing){
      attachController();
    } else {
      detachController();
    }*/

    invalidate();
  }

  public void setSurfacePosition(Rect rect)
  {
    mSurfacePosition = rect;
  }

  @Override
  public void draw(Canvas canvas)
  {
    super.draw(canvas);

    if(mOverlayMask == 0 || !mShowingOverlay)
      return;

    for (InputOverlayDrawableButton button : overlayButtons)
    {
      if((button.getRole() & mOverlayMask) == 0)
        continue;

      button.draw(canvas);
    }

    for (InputOverlayDrawableDpad dpad : overlayDpads)
    {
      dpad.draw(canvas);
    }

    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
    {
      if(joystick.getXControl() !=m_dynamic_joystick||m_dynamic_joystick_pressed)
      joystick.draw(canvas);
    }
  }

  void handle_left_joystick_event(float x, float y){

    if(x!=0){
      if(x<0){
        Emulator.get.key_event(ControlId.lsr,false);
        Emulator.get.key_event(ControlId.lsl,true,(int)Math.abs(x*255.0));
      }
      else{
        Emulator.get.key_event(ControlId.lsl,false);
        Emulator.get.key_event(ControlId.lsr,true,(int)Math.abs(x*255.0));
      }
    }
    else{
      Emulator.get.key_event(ControlId.lsr,false);
      Emulator.get.key_event(ControlId.lsl,false);
    }

    if(y!=0){
      if(y<0){
        Emulator.get.key_event(ControlId.lsd,false);
        Emulator.get.key_event(ControlId.lsu,true,(int)Math.abs(y*255.0));
      }else{
        Emulator.get.key_event(ControlId.lsu,false);
        Emulator.get.key_event(ControlId.lsd,true,(int)Math.abs(y*255.0));
      }
    }
    else{
      Emulator.get.key_event(ControlId.lsd,false);
      Emulator.get.key_event(ControlId.lsu,false);
    }
  }

  void handle_right_joystick_event(float x, float y){
    if(x!=0){
      if(x<0){
        Emulator.get.key_event(ControlId.rsr,false);
        Emulator.get.key_event(ControlId.rsl,true,(int)Math.abs(x*255.0));
      }else{
        Emulator.get.key_event(ControlId.rsl,false);
        Emulator.get.key_event(ControlId.rsr,true,(int)Math.abs(x*255.0));
      }
    }
    else{
      Emulator.get.key_event(ControlId.rsr,false);
      Emulator.get.key_event(ControlId.rsl,false);
    }

    if(y!=0){
      if(y<0){
        Emulator.get.key_event(ControlId.rsd,false);
        Emulator.get.key_event(ControlId.rsu,true,(int)Math.abs(y*255.0));
      }else{
        Emulator.get.key_event(ControlId.rsu,false);
        Emulator.get.key_event(ControlId.rsd,true,(int)Math.abs(y*255.0));
      }
    }
    else{
      Emulator.get.key_event(ControlId.rsd,false);
      Emulator.get.key_event(ControlId.rsu,false);
    }
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    if(mOverlayMask == 0)
      return false;

    resetHideTimer();

    if (isInEditMode())
    {
      return onTouchWhileEditing(event);
    }

    int action = event.getActionMasked();
    boolean firstPointer = action != MotionEvent.ACTION_POINTER_DOWN &&
            action != MotionEvent.ACTION_POINTER_UP;
    int pointerIndex = firstPointer ? 0 : event.getActionIndex();
    // track if the overlay is concerned this this action
    boolean concerned = false;
    boolean skip_dynamic_joystick_pressed = false;

    for (InputOverlayDrawableButton button : overlayButtons)
    {
      if((button.getRole() & mOverlayMask) == 0)
        continue;

      // Determine the button state to apply based on the MotionEvent action flag.
      switch (action)
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          // If a pointer enters the bounds of a button, press that button.
          if (button.getBounds()
                  .contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex)))
          {
            button.setPressedState(true);
            button.setTrackId(event.getPointerId(pointerIndex));
            concerned = true;
            skip_dynamic_joystick_pressed = true;
            /*if(button.getRole() == OVERLAY_MASK_TOUCH_SCREEN_SWITCH)
              setTouchState(button.getPressed());
            else*/
				Emulator.get.key_event(button.getControl(), true);
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          // If a pointer ends, release the button it was pressing.
          if (button.getTrackId() == event.getPointerId(pointerIndex))
          {
            button.setPressedState(false);
            if(button.getRole() != OVERLAY_MASK_TOUCH_SCREEN_SWITCH)
				  Emulator.get.key_event(button.getControl(), false);

            button.setTrackId(-1);
            concerned = true;
          }
          break;
      }
    }

    for (InputOverlayDrawableDpad dpad : overlayDpads)
    {
      // Determine the button state to apply based on the MotionEvent action flag.
      switch (event.getAction() & MotionEvent.ACTION_MASK)
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          // If a pointer enters the bounds of a button, press that button.
          if (dpad.getBounds()
                  .contains((int) event.getX(pointerIndex), (int) event.getY(pointerIndex)))
          {
            dpad.setTrackId(event.getPointerId(pointerIndex));
            skip_dynamic_joystick_pressed = true;
            concerned = true;
          }
        case MotionEvent.ACTION_MOVE:
          if (dpad.getTrackId() == event.getPointerId(pointerIndex))
          {
            concerned = true;
            // Up, Down, Left, Right
            boolean[] dpadPressed = {false, false, false, false};

            if (dpad.getBounds().top + (dpad.getHeight() / 3) > (int) event.getY(pointerIndex))
              dpadPressed[0] = true;
            if (dpad.getBounds().bottom - (dpad.getHeight() / 3) < (int) event.getY(pointerIndex))
              dpadPressed[1] = true;
            if (dpad.getBounds().left + (dpad.getWidth() / 3) > (int) event.getX(pointerIndex))
              dpadPressed[2] = true;
            if (dpad.getBounds().right - (dpad.getWidth() / 3) < (int) event.getX(pointerIndex))
              dpadPressed[3] = true;

            // Release the buttons first, then press
            /*for (int i = 0; i < dpadPressed.length; i++)
            {
              if (!dpadPressed[i])
              {
                setButton(dpad.getControl(i), false);
              }
            }*/
            // Press buttons
            for (int i = 0; i < dpadPressed.length; i++)
            {
              if (dpadPressed[i])
              {
                skip_dynamic_joystick_pressed = true;
				  Emulator.get.key_event(dpad.getControl(i), true);
              }
              else{
                Emulator.get.key_event(dpad.getControl(i), false);
              }
            }
            setDpadState(dpad, dpadPressed[0], dpadPressed[1], dpadPressed[2], dpadPressed[3]);
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          // If a pointer ends, release the buttons.
          if (dpad.getTrackId() == event.getPointerId(pointerIndex))
          {
            concerned = true;
            for (int i = 0; i < 4; i++)
            {
              dpad.setState(InputOverlayDrawableDpad.STATE_DEFAULT);
				Emulator.get.key_event(dpad.getControl(i), false);
            }
            dpad.setTrackId(-1);
          }
          break;
      }
    }

    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
    {

      int control=joystick.getXControl();
      if (joystick.TrackEvent(event)&&control!=m_dynamic_joystick)
      {
        concerned = true;
        skip_dynamic_joystick_pressed = true;
        
        /*int joyX = Math.round(joystick.getX() * (1 << 15));
        joyX = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, joyX));
        int joyY = Math.round(joystick.getY() * (1 << 15));
        joyY = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, joyY));*/

        //左摇杆
		if(control==0){
			  float  x=joystick.getX();
              float  y=joystick.getY();
              handle_left_joystick_event(x,y);
		  }
        //右摇杆
		else if(control==1){
          float  x=joystick.getX();
          float  y=joystick.getY();
			handle_right_joystick_event(x,y);
		  }
		  }
    }

    //动态摇杆
    {

      InputOverlayDrawableJoystick dynamic_joystick=null;
      for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
        if(joystick.getXControl()==m_dynamic_joystick)
          dynamic_joystick=joystick;


        if(dynamic_joystick!=null){

          if(!skip_dynamic_joystick_pressed&&(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN)){
            int touchX = (int)event.getX(pointerIndex);
            int touchY = (int)event.getY(pointerIndex);
            int w=dynamic_joystick.getWidth();
            int h=dynamic_joystick.getHeight();
            int l=touchX-(w>>1);
            int t=touchY-(h>>1);
            int r=l+w;
            int d=t+h;
            dynamic_joystick.updateBounds(l,t,r,d);
            dynamic_joystick.setPosition(l,t);
          }

          if (dynamic_joystick.TrackEvent(event)){
            concerned=true;
            m_dynamic_joystick_pressed=true;
            if(m_dynamic_joystick==0)
              handle_left_joystick_event(dynamic_joystick.getX(),dynamic_joystick.getY());
            else if(m_dynamic_joystick==1)
              handle_right_joystick_event(dynamic_joystick.getX(),dynamic_joystick.getY());
          }
          else{
            if(m_dynamic_joystick_pressed){

              if(m_dynamic_joystick==0)
                handle_left_joystick_event(0,0);
              else if(m_dynamic_joystick==1)
                handle_right_joystick_event(0,0);
              concerned=true;
            }

            m_dynamic_joystick_pressed=false;
          }

        }
    }

    if(concerned)
      invalidate();

    return concerned;
  }

  public boolean onTouchWhileEditing(MotionEvent event)
  {
    int pointerIndex = event.getActionIndex();
    int fingerPositionX = (int) event.getX(pointerIndex);
    int fingerPositionY = (int) event.getY(pointerIndex);

    String orientation = "";

    // Maybe combine Button and Joystick as subclasses of the same parent?
    // Or maybe create an interface like IMoveableHUDControl?
    boolean intersect = false;

    for (InputOverlayDrawableButton button : overlayButtons)
    {
      // Determine the button state to apply based on the MotionEvent action flag.
      switch (event.getAction() & MotionEvent.ACTION_MASK)
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          // If no button is being moved now, remember the currently touched button to move.
          if (mButtonBeingConfigured == null &&
                  button.getBounds().contains(fingerPositionX, fingerPositionY))
          {
            mButtonBeingConfigured = button;
            mButtonBeingConfigured.onConfigureTouch(event);
            intersect = true;
          }
          break;
        case MotionEvent.ACTION_MOVE:
          if (mButtonBeingConfigured != null)
          {
            mButtonBeingConfigured.onConfigureTouch(event);
            invalidate();
            return true;
          }
          break;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          if (mButtonBeingConfigured == button)
          {
            // Persist button position by saving new place.
            saveControlPosition(mButtonBeingConfigured.getLegacyId(),
                    mButtonBeingConfigured.getBounds().left,
                    mButtonBeingConfigured.getBounds().top, orientation);
            mButtonBeingConfigured = null;
            intersect = true;
          }
          break;
      }
    }

    for (InputOverlayDrawableDpad dpad : overlayDpads)
    {
      // Determine the button state to apply based on the MotionEvent action flag.
      switch (event.getAction() & MotionEvent.ACTION_MASK)
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          // If no button is being moved now, remember the currently touched button to move.
          if (mButtonBeingConfigured == null &&
                  dpad.getBounds().contains(fingerPositionX, fingerPositionY))
          {
            mDpadBeingConfigured = dpad;
            mDpadBeingConfigured.onConfigureTouch(event);
            intersect = true;
          }
          break;
        case MotionEvent.ACTION_MOVE:
          if (mDpadBeingConfigured != null)
          {
            mDpadBeingConfigured.onConfigureTouch(event);
            invalidate();
            return true;
          }
          break;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          if (mDpadBeingConfigured == dpad)
          {
            // Persist button position by saving new place.
            saveControlPosition(mDpadBeingConfigured.getLegacyId(),
                    mDpadBeingConfigured.getBounds().left, mDpadBeingConfigured.getBounds().top,
                    orientation);
            mDpadBeingConfigured = null;
            intersect = true;
          }
          break;
      }
    }

    for (InputOverlayDrawableJoystick joystick : overlayJoysticks)
    {
      if(joystick.getXControl()==m_dynamic_joystick)
        continue;

      switch (event.getAction())
      {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
          if (mJoystickBeingConfigured == null &&
                  joystick.getBounds().contains(fingerPositionX, fingerPositionY))
          {
            mJoystickBeingConfigured = joystick;
            mJoystickBeingConfigured.onConfigureTouch(event);
            intersect = true;
          }
          break;
        case MotionEvent.ACTION_MOVE:
          if (mJoystickBeingConfigured != null)
          {
            mJoystickBeingConfigured.onConfigureTouch(event);
            invalidate();
            intersect = true;
          }
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
          if (mJoystickBeingConfigured != null)
          {
            saveControlPosition(mJoystickBeingConfigured.getLegacyId(),
                    mJoystickBeingConfigured.getBounds().left,
                    mJoystickBeingConfigured.getBounds().top, orientation);
            mJoystickBeingConfigured = null;
            intersect = true;
          }
          break;
      }
    }

    return intersect;
  }

  public void onDestroy()
  {
  }

  private void setDpadState(InputOverlayDrawableDpad dpad, boolean up, boolean down, boolean left,
          boolean right)
  {
    if (up)
    {
      if (left)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP_LEFT);
      else if (right)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP_RIGHT);
      else
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_UP);
    }
    else if (down)
    {
      if (left)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN_LEFT);
      else if (right)
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN_RIGHT);
      else
        dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_DOWN);
    }
    else if (left)
    {
      dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_LEFT);
    }
    else if (right)
    {
      dpad.setState(InputOverlayDrawableDpad.STATE_PRESSED_RIGHT);
    }
  }


  private void addVitaOverlayControls(String orientation)
  {

    final SharedPreferences sPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());

    //mGlobalScale=sPrefs.getFloat("mGlobalScale",1.0f);

    m_dynamic_joystick=sPrefs.getInt("m_dynamic_joystick",-1);

    m_joystick_scale=sPrefs.getFloat("m_joystick_scale",1.0f);
    m_dpad_scale=sPrefs.getFloat("m_dpad_scale",1.0f);
    m_abxy_scale=sPrefs.getFloat("m_abxy_scale",1.0f);
    m_lr_scale=sPrefs.getFloat("m_lr_scale",1.0f);
    m_ss_scale=sPrefs.getFloat("m_ss_scale",1.0f);
    m_ps_scale=sPrefs.getFloat("m_ps_scale",1.0f);

    m_left_joystick_enabled=sPrefs.getBoolean("m_left_joystick_enabled",true);
    m_right_joystick_enabled=sPrefs.getBoolean("m_right_joystick_enabled",true);
    m_dpad_enabled=sPrefs.getBoolean("m_dpad_enabled",true);
    m_square_enabled=sPrefs.getBoolean("m_square_enabled",true);
    m_cross_enabled=sPrefs.getBoolean("m_cross_enabled",true);
    m_triangle_enabled=sPrefs.getBoolean("m_triangle_enabled",true);
    m_circle_enabled=sPrefs.getBoolean("m_circle_enabled",true);
    m_start_enabled=sPrefs.getBoolean("m_start_enabled",true);
    m_select_enabled=sPrefs.getBoolean("m_select_enabled",true);
    m_l1_enabled=sPrefs.getBoolean("m_l1_enabled",true);
    m_r1_enabled=sPrefs.getBoolean("m_r1_enabled",true);
    m_l2_enabled=sPrefs.getBoolean("m_l2_enabled",true);
    m_r2_enabled=sPrefs.getBoolean("m_r2_enabled",true);
    m_l3_enabled=sPrefs.getBoolean("m_l3_enabled",true);
    m_r3_enabled=sPrefs.getBoolean("m_r3_enabled",true);
    m_ps_enabled=sPrefs.getBoolean("m_ps_enabled",true);

    if(m_cross_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_cross,
              R.drawable.button_cross_pressed, ButtonType.BUTTON_CROSS, ControlId.cross,
              orientation, OVERLAY_MASK_BASIC));

    if(m_circle_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_circle,
              R.drawable.button_circle_pressed, ButtonType.BUTTON_CIRCLE, ControlId.circle,
              orientation, OVERLAY_MASK_BASIC));

    if(m_square_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_square,
              R.drawable.button_square_pressed, ButtonType.BUTTON_SQUARE, ControlId.square,
              orientation, OVERLAY_MASK_BASIC));

    if(m_triangle_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_triangle,
              R.drawable.button_triangle_pressed, ButtonType.BUTTON_TRIANGLE, ControlId.triangle,
              orientation, OVERLAY_MASK_BASIC));

    if(m_start_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_start,
              R.drawable.button_start_pressed, ButtonType.BUTTON_START,
              ControlId.start, orientation, OVERLAY_MASK_BASIC));

    if(m_select_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_select,
              R.drawable.button_select_pressed, ButtonType.BUTTON_SELECT,
              ControlId.select, orientation, OVERLAY_MASK_BASIC));

    if(m_l1_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l,
              R.drawable.button_l_pressed, ButtonType.TRIGGER_L,
              ControlId.l1, orientation, OVERLAY_MASK_BASIC));

    if(m_r1_enabled)
      overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r,
              R.drawable.button_r_pressed, ButtonType.TRIGGER_R,
              ControlId.r1, orientation, OVERLAY_MASK_BASIC));

    if(m_l2_enabled)
    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l2,
            R.drawable.button_l2_pressed, ButtonType.TRIGGER_L2,
												 ControlId.l2, orientation, OVERLAY_MASK_BASIC));

    if(m_r2_enabled)
    overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r2,
            R.drawable.button_r2_pressed, ButtonType.TRIGGER_R2,
												 ControlId.r2, orientation, OVERLAY_MASK_BASIC));

    if(m_l3_enabled)
	  overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_l3,
												 R.drawable.button_l3_pressed, ButtonType.TRIGGER_L3,
												 ControlId.l3, orientation, OVERLAY_MASK_BASIC));

    if(m_r3_enabled)
	  overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_r3,
												 R.drawable.button_r3_pressed, ButtonType.TRIGGER_R3,
												 ControlId.r3, orientation, OVERLAY_MASK_BASIC));

    if(m_ps_enabled)
	  overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.app_icon,
												 R.drawable.app_icon, ButtonType.BUTTON_PS,
												 ControlId.ps, orientation, OVERLAY_MASK_BASIC));

	  
    /*overlayButtons.add(initializeOverlayButton(getContext(), R.drawable.button_touch_f,
            R.drawable.button_touch_b, ButtonType.BUTTON_TOUCH_SWITCH,
            ControlId.touch, orientation, OVERLAY_MASK_TOUCH_SCREEN_SWITCH));
    */
    if(m_dpad_enabled)
      overlayDpads.add(initializeOverlayDpad(getContext(), R.drawable.dpad_idle,
              R.drawable.dpad_up,
              R.drawable.dpad_up_left,
              ButtonType.DPAD_UP, ControlId.u, ControlId.d,
              ControlId.l, ControlId.r, orientation));

    if(m_left_joystick_enabled)
      overlayJoysticks.add(initializeOverlayJoystick(getContext(), R.drawable.joystick_range,
              R.drawable.joystick, R.drawable.joystick_pressed,
              ButtonType.STICK_LEFT, 0,0,/*ControlId.axis_left_x,
              ControlId.axis_left_y,*/ orientation));

    if(m_right_joystick_enabled)
      overlayJoysticks.add(initializeOverlayJoystick(getContext(), R.drawable.joystick_range,
              R.drawable.joystick, R.drawable.joystick_pressed,
              ButtonType.STICK_RIGHT, 1,1,/*ControlId.axis_right_x,
              ControlId.axis_right_y,*/ orientation));
  }

  public void refreshControls()
  {

    // Remove all the overlay buttons from the HashSet.
    overlayButtons.clear();
    overlayDpads.clear();
    overlayJoysticks.clear();

    String orientation = "";
    addVitaOverlayControls(orientation);

    invalidate();
  }

  public void resetButtonPlacement()
  {
    vitaDefaultOverlay();
    refreshControls();
  }
  /*
  public float getScale(){
    return mGlobalScale;
  }

  public void setScale(float scale){
    if(scale != mGlobalScale){
      mGlobalScale = scale;
      SharedPreferences.Editor sPrefsEditor = mPreferences.edit();
      sPrefsEditor.putFloat("mGlobalScale",mGlobalScale);
      sPrefsEditor.commit();
      refreshControls();
    }
  }*/
  public float getScale(int scale_type){
    switch(scale_type){
      case ScaleType.JOYSTICK:
        return m_joystick_scale;
        case ScaleType.DPAD:
          return m_dpad_scale;
          case ScaleType.ABXY:
            return m_abxy_scale;
      case ScaleType.START_SELECT:
              return m_ss_scale;
              case ScaleType.LR:
                return m_lr_scale;
              case ScaleType.PS:
                return m_ps_scale;

    }
    throw new IllegalArgumentException("Invalid scale type");
  }

  public void setScale(int scale_type,float scale){
    switch (scale_type){
      case ScaleType.JOYSTICK:
        m_joystick_scale = scale;
        break;
      case ScaleType.DPAD:
        m_dpad_scale = scale;
        break;
      case ScaleType.ABXY:
        m_abxy_scale = scale;
        break;
      case ScaleType.START_SELECT:
        m_ss_scale = scale;
        break;
      case ScaleType.LR:
        m_lr_scale = scale;
        break;
      case ScaleType.PS:
          m_ps_scale = scale;
    }
    {
      SharedPreferences.Editor sPrefsEditor = mPreferences.edit();
      sPrefsEditor.putFloat("m_joystick_scale",m_joystick_scale);
      sPrefsEditor.putFloat("m_dpad_scale",m_dpad_scale);
      sPrefsEditor.putFloat("m_abxy_scale",m_abxy_scale);
      sPrefsEditor.putFloat("m_ss_scale",m_ss_scale);
      sPrefsEditor.putFloat("m_lr_scale",m_lr_scale);
      sPrefsEditor.putFloat("m_ps_scale",m_ps_scale);
      sPrefsEditor.commit();
      refreshControls();
    }
  }

  //-1 disabled
  //0 left joystick
  //1 right joystick
  public void set_dynamic_joystick(int dynamic_joystick){
    m_dynamic_joystick = dynamic_joystick;

    SharedPreferences.Editor sPrefsEditor = mPreferences.edit();
    sPrefsEditor.putInt("m_dynamic_joystick",m_dynamic_joystick);
    sPrefsEditor.commit();
    refreshControls();
  }

  public int get_dynamic_joystick(){
    return m_dynamic_joystick;
  }

  public void setOpacity(int opacity){
    if(opacity != mGlobalOpacity){
      mGlobalOpacity = opacity;
      refreshControls();
    }
  }

  private void saveControlPosition(int sharedPrefsId, int x, int y,
          String orientation)
  {
    final SharedPreferences sPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());
    SharedPreferences.Editor sPrefsEditor = sPrefs.edit();
    sPrefsEditor.putFloat(getXKey(sharedPrefsId, orientation), x);
    sPrefsEditor.putFloat(getYKey(sharedPrefsId, orientation), y);
    sPrefsEditor.apply();
  }

  private static String getKey(int sharedPrefsId, String orientation, String suffix)
  {
      return sharedPrefsId + orientation + suffix;
  }

  private static String getXKey(int sharedPrefsId, String orientation)
  {
    return getKey(sharedPrefsId, orientation, "-X");
  }

  private static String getYKey(int sharedPrefsId, String orientation)
  {
    return getKey(sharedPrefsId, orientation, "-Y");
  }

  /**
   * Initializes an InputOverlayDrawableButton, given by resId, with all of the
   * parameters set for it to be properly shown on the InputOverlay.
   * <p>
   * This works due to the way the X and Y coordinates are stored within
   * the {@link SharedPreferences}.
   * <p>
   * In the input overlay configuration menu,
   * once a touch event begins and then ends (ie. Organizing the buttons to one's own liking for the overlay).
   * the X and Y coordinates of the button at the END of its touch event
   * (when you remove your finger/stylus from the touchscreen) are then stored
   * within a SharedPreferences instance so that those values can be retrieved here.
   * <p>
   * This has a few benefits over the conventional way of storing the values
   * (ie. within the Dolphin ini file).
   * <ul>
   * <li>No native calls</li>
   * <li>Keeps Android-only values inside the Android environment</li>
   * </ul>
   * <p>
   * Technically no modifications should need to be performed on the returned
   * InputOverlayDrawableButton. Simply add it to the HashSet of overlay items and wait
   * for Android to call the onDraw method.
   *
   * @param context      The current {@link Context}.
   * @param defaultResId The resource ID of the {@link Drawable} to get the {@link Bitmap} of (Default State).
   * @param pressedResId The resource ID of the {@link Drawable} to get the {@link Bitmap} of (Pressed State).
   * @param legacyId     Legacy identifier for the button the InputOverlayDrawableButton represents.
   * @param control      Control identifier for the button the InputOverlayDrawableButton represents.
   * @return An {@link InputOverlayDrawableButton} with the correct drawing bounds set.
   */
  private static InputOverlayDrawableButton initializeOverlayButton(Context context,
          int defaultResId, int pressedResId, int legacyId, int control, String orientation, int role)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // SharedPreference to retrieve the X and Y coordinates for the InputOverlayDrawableButton.
    final SharedPreferences sPrefs = PreferenceManager.getDefaultSharedPreferences(context);

    // Decide scale based on button ID and user preference
    float scale = 0.15f*m_abxy_scale;

    if(legacyId == ButtonType.TRIGGER_L
            || legacyId == ButtonType.TRIGGER_R
            || legacyId == ButtonType.TRIGGER_L2
            || legacyId == ButtonType.TRIGGER_R2
		    || legacyId == ButtonType.TRIGGER_L3
		    || legacyId == ButtonType.TRIGGER_R3)
      scale = 0.20f*m_lr_scale;
      else if(legacyId == ButtonType.BUTTON_START
            || legacyId == ButtonType.BUTTON_SELECT)
      scale = 0.20f*m_ss_scale;
    else if(legacyId == ButtonType.BUTTON_PS)
      scale = 0.06f*m_ps_scale;

    //scale *= mGlobalScale;

    // Initialize the InputOverlayDrawableButton.
    final Bitmap defaultStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, defaultResId), scale);
    final Bitmap pressedStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedResId), scale);
    final InputOverlayDrawableButton overlayDrawable =
            new InputOverlayDrawableButton(res, defaultStateBitmap, pressedStateBitmap, legacyId,
                    control, role);

    // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
    // These were set in the input overlay configuration menu.
    int drawableX = (int) sPrefs.getFloat(getXKey(legacyId, orientation), 0f);
    int drawableY = (int) sPrefs.getFloat(getYKey(legacyId, orientation), 0f);

    int width = overlayDrawable.getWidth();
    int height = overlayDrawable.getHeight();

    // Now set the bounds for the InputOverlayDrawableButton.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableButton will be.
    overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height);

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (mGlobalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  /**
   * Initializes an {@link InputOverlayDrawableDpad}
   *
   * @param context                   The current {@link Context}.
   * @param defaultResId              The {@link Bitmap} resource ID of the default sate.
   * @param pressedOneDirectionResId  The {@link Bitmap} resource ID of the pressed sate in one direction.
   * @param pressedTwoDirectionsResId The {@link Bitmap} resource ID of the pressed sate in two directions.
   * @param legacyId                  Legacy identifier for the up button.
   * @param upControl                 Control identifier for the up button.
   * @param downControl               Control identifier for the down button.
   * @param leftControl               Control identifier for the left button.
   * @param rightControl              Control identifier for the right button.
   * @return the initialized {@link InputOverlayDrawableDpad}
   */
  private static InputOverlayDrawableDpad initializeOverlayDpad(Context context,
          int defaultResId,
          int pressedOneDirectionResId,
          int pressedTwoDirectionsResId,
          int legacyId,
          int upControl,
          int downControl,
          int leftControl,
          int rightControl,
          String orientation)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // SharedPreference to retrieve the X and Y coordinates for the InputOverlayDrawableDpad.
    final SharedPreferences sPrefs = PreferenceManager.getDefaultSharedPreferences(context);

    // Decide scale based on button ID and user preference
    float scale = 0.35f;

    scale *= m_dpad_scale;

    // Initialize the InputOverlayDrawableDpad.
    final Bitmap defaultStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, defaultResId), scale);
    final Bitmap pressedOneDirectionStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedOneDirectionResId),
                    scale);
    final Bitmap pressedTwoDirectionsStateBitmap =
            resizeBitmap(context, BitmapFactory.decodeResource(res, pressedTwoDirectionsResId),
                    scale);
    final InputOverlayDrawableDpad overlayDrawable =
            new InputOverlayDrawableDpad(res, defaultStateBitmap,
                    pressedOneDirectionStateBitmap, pressedTwoDirectionsStateBitmap,
                    legacyId, upControl, downControl, leftControl, rightControl);

    // The X and Y coordinates of the InputOverlayDrawableDpad on the InputOverlay.
    // These were set in the input overlay configuration menu.
    int drawableX = (int) sPrefs.getFloat(getXKey(legacyId, orientation), 0f);
    int drawableY = (int) sPrefs.getFloat(getYKey(legacyId, orientation), 0f);

    int width = overlayDrawable.getWidth();
    int height = overlayDrawable.getHeight();

    // Now set the bounds for the InputOverlayDrawableDpad.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableDpad will be.
    overlayDrawable.setBounds(drawableX, drawableY, drawableX + width, drawableY + height);

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (mGlobalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  /**
   * Initializes an {@link InputOverlayDrawableJoystick}
   *
   * @param context         The current {@link Context}
   * @param resOuter        Resource ID for the outer image of the joystick (the static image that shows the circular bounds).
   * @param defaultResInner Resource ID for the default inner image of the joystick (the one you actually move around).
   * @param pressedResInner Resource ID for the pressed inner image of the joystick.
   * @param legacyId        Legacy identifier (ButtonType) for which joystick this is.
   * @param xControl        Control identifier for the X axis.
   * @param yControl        Control identifier for the Y axis.
   * @return the initialized {@link InputOverlayDrawableJoystick}.
   */
  private static InputOverlayDrawableJoystick initializeOverlayJoystick(Context context,
          int resOuter, int defaultResInner, int pressedResInner, int legacyId, int xControl,
          int yControl, String orientation)
  {
    // Resources handle for fetching the initial Drawable resource.
    final Resources res = context.getResources();

    // SharedPreference to retrieve the X and Y coordinates for the InputOverlayDrawableJoystick.
    final SharedPreferences sPrefs = PreferenceManager.getDefaultSharedPreferences(context);

    // Decide scale based on user preference
    float scale = 0.275f;
    scale *= m_joystick_scale;

    // Initialize the InputOverlayDrawableJoystick.
    final Bitmap bitmapOuter =
            resizeBitmap(context, BitmapFactory.decodeResource(res, resOuter), scale);
    final Bitmap bitmapInnerDefault = BitmapFactory.decodeResource(res, defaultResInner);
    final Bitmap bitmapInnerPressed = BitmapFactory.decodeResource(res, pressedResInner);

    // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
    // These were set in the input overlay configuration menu.
    int drawableX = (int) sPrefs.getFloat(getXKey(legacyId, orientation), 0f);
    int drawableY = (int) sPrefs.getFloat(getYKey(legacyId, orientation), 0f);

    // Decide inner scale based on joystick ID
    float innerScale = 1.375f;

    // Now set the bounds for the InputOverlayDrawableJoystick.
    // This will dictate where on the screen (and the what the size) the InputOverlayDrawableJoystick will be.
    int outerSize = bitmapOuter.getWidth();
    Rect outerRect = new Rect(drawableX, drawableY, drawableX + outerSize, drawableY + outerSize);
    Rect innerRect = new Rect(0, 0, (int) (outerSize / innerScale), (int) (outerSize / innerScale));

    // Send the drawableId to the joystick so it can be referenced when saving control position.
    final InputOverlayDrawableJoystick overlayDrawable =
            new InputOverlayDrawableJoystick(res, bitmapOuter, bitmapInnerDefault,
                    bitmapInnerPressed, outerRect, innerRect, legacyId, xControl, yControl);

    // Need to set the image's position
    overlayDrawable.setPosition(drawableX, drawableY);
    overlayDrawable.setOpacity((int) (mGlobalOpacity * 0.01 * 255));

    return overlayDrawable;
  }

  public void setIsInEditMode(boolean isInEditMode)
  {
    mIsInEditMode = isInEditMode;
  }

  public boolean isInEditMode()
  {
    return mIsInEditMode;
  }

  private void defaultOverlay()
  {
    if (!mPreferences.getBoolean("OverlayInit", false))
    {
        vitaDefaultOverlay();
    }

    SharedPreferences.Editor sPrefsEditor = mPreferences.edit();
    sPrefsEditor.putBoolean("OverlayInit", true);
    sPrefsEditor.apply();
  }

  private void vitaDefaultOverlay()
  {
    SharedPreferences.Editor sPrefsEditor = mPreferences.edit();

    // Get screen size
    Display display = ((Activity) getContext()).getWindowManager().getDefaultDisplay();
    DisplayMetrics outMetrics = new DisplayMetrics();
    display.getMetrics(outMetrics);
    float maxX = outMetrics.heightPixels;
    float maxY = outMetrics.widthPixels;
    // Height and width changes depending on orientation. Use the larger value for maxX.
    if (maxY > maxX)
    {
      float tmp = maxX;
      maxX = maxY;
      maxY = tmp;
    }
    Resources res = getResources();

    // Each value is a percent from max X/Y stored as an int. Have to bring that value down
    // to a decimal before multiplying by MAX X/Y.
    sPrefsEditor.putFloat(ButtonType.BUTTON_CROSS + "-X",
            (((float) res.getInteger(R.integer.BUTTON_CROSS_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_CROSS + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_CROSS_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.BUTTON_CIRCLE + "-X",
            (((float) res.getInteger(R.integer.BUTTON_CIRCLE_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_CIRCLE + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_CIRCLE_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.BUTTON_SQUARE + "-X",
            (((float) res.getInteger(R.integer.BUTTON_SQUARE_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_SQUARE + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_SQUARE_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.BUTTON_TRIANGLE + "-X",
            (((float) res.getInteger(R.integer.BUTTON_TRIANGLE_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_TRIANGLE + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_TRIANGLE_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.BUTTON_SELECT + "-X",
            (((float) res.getInteger(R.integer.BUTTON_SELECT_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_SELECT + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_SELECT_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.BUTTON_START + "-X",
            (((float) res.getInteger(R.integer.BUTTON_START_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_START + "-Y",
            (((float) res.getInteger(R.integer.BUTTON_START_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.DPAD_UP + "-X",
            (((float) res.getInteger(R.integer.DPAD_UP_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.DPAD_UP + "-Y",
            (((float) res.getInteger(R.integer.DPAD_UP_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.STICK_LEFT + "-X",
            (((float) res.getInteger(R.integer.STICK_LEFT_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.STICK_LEFT + "-Y",
            (((float) res.getInteger(R.integer.STICK_LEFT_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.STICK_RIGHT + "-X",
            (((float) res.getInteger(R.integer.STICK_RIGHT_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.STICK_RIGHT + "-Y",
            (((float) res.getInteger(R.integer.STICK_RIGHT_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_L + "-X",
            (((float) res.getInteger(R.integer.TRIGGER_L_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_L + "-Y",
            (((float) res.getInteger(R.integer.TRIGGER_L_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_R + "-X",
            (((float) res.getInteger(R.integer.TRIGGER_R_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_R + "-Y",
            (((float) res.getInteger(R.integer.TRIGGER_R_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_L2 + "-X",
            (((float) res.getInteger(R.integer.TRIGGER_L2_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_L2 + "-Y",
            (((float) res.getInteger(R.integer.TRIGGER_L2_Y) / 1000) * maxY));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_R2 + "-X",
            (((float) res.getInteger(R.integer.TRIGGER_R2_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.TRIGGER_R2 + "-Y",
            (((float) res.getInteger(R.integer.TRIGGER_R2_Y) / 1000) * maxY));
			
	  sPrefsEditor.putFloat(ButtonType.TRIGGER_L3 + "-X",
							(((float) res.getInteger(R.integer.TRIGGER_L3_X) / 1000) * maxX));
	  sPrefsEditor.putFloat(ButtonType.TRIGGER_L3 + "-Y",
							(((float) res.getInteger(R.integer.TRIGGER_L3_Y) / 1000) * maxY));
	  sPrefsEditor.putFloat(ButtonType.TRIGGER_R3 + "-X",
							(((float) res.getInteger(R.integer.TRIGGER_R3_X) / 1000) * maxX));
	  sPrefsEditor.putFloat(ButtonType.TRIGGER_R3 + "-Y",
							(((float) res.getInteger(R.integer.TRIGGER_R3_Y) / 1000) * maxY));
	  
    sPrefsEditor.putFloat(ButtonType.BUTTON_PS + "-X",
            (((float) res.getInteger(R.integer.BUTTON_PS_X) / 1000) * maxX));
    sPrefsEditor.putFloat(ButtonType.BUTTON_PS+ "-Y",
							(((float) res.getInteger(R.integer.BUTTON_PS_Y) / 1000) * maxY));

    sPrefsEditor.putFloat("mGlobalScale",
            1.0f);

    // We want to commit right away, otherwise the overlay could load before this is saved.
    sPrefsEditor.commit();
  }

  /*public native void attachController();
  public native void detachController();
  public native void setAxis(int axis, short value);
  public native void setButton(int button, boolean value);
  public native void setTouchState(boolean is_back);*/
	/*enum class APS3E_VKC:u32{
		none=0,
		l,u,r,d,
		square,cross,circle,triangle,
		lsl,lsu,lsr,lsd,
		rsl,rsu,rsr,rsd,
		l1,l2,l3,
		r1,r2,r3,
		start,select,
	};*/

  public static final class ScaleType {
    public static final int JOYSTICK = 0;
    public static final int DPAD = 1;
    public static final int ABXY = 2;
    public static final int START_SELECT = 3;
    public static final int LR = 4;
    public static final int PS = 5;
  }
	public static final class ButtonType
	{
		public static final int BUTTON_CROSS = 0;
		public static final int BUTTON_CIRCLE = 1;
		public static final int BUTTON_SQUARE = 2;
		public static final int BUTTON_TRIANGLE = 3;
		public static final int BUTTON_SELECT = 4;
		public static final int BUTTON_START = 5;
		public static final int DPAD_UP = 6;
		public static final int STICK_LEFT = 10;
		public static final int STICK_RIGHT = 15;
		public static final int TRIGGER_L = 20;
		public static final int TRIGGER_R = 21;
		public static final int TRIGGER_L2 = 22;
		public static final int TRIGGER_R2 = 23;
		public static final int TRIGGER_L3 = 24;
		public static final int TRIGGER_R3 = 25;
		public static final int BUTTON_PS = 666;
		//public static final int BUTTON_TOUCH_SWITCH = 1024;
	}
  // SDL values
  public static class ControlId {
	  
		//dpad
		public static final int l = 1;
		public static final int u = 2;
		public static final int r = 3;
		public static final int d = 4;
		
		public static final int square = 5;
		public static final int cross = 6;
		public static final int circle = 7;
		public static final int triangle = 8;
		//左摇杆
		public static final int lsl = 9;
		public static final int lsu = 10;
		public static final int lsr = 11;
		public static final int lsd = 12;
		//右摇杆
		public static final int rsl = 13;
		public static final int rsu = 14;
		public static final int rsr = 15;
		public static final int rsd = 16;
		
		public static final int l1 = 17;
		/*public static final int r1 = 18;
		public static final int l2 = 19;
		public static final int r2 = 20;
		public static final int l3 = 21;
		public static final int r3 = 22;*/
		public static final int l2 = 18;
		public static final int l3 = 19;
		public static final int r1 = 20;
		public static final int r2 = 21;
		public static final int r3 = 22;

		public static final int start = 23;
		public static final int select = 24;
		
		public static final int ps = 25;
		
		/*public static final int axis_left_x = 0;
		public static final int axis_left_y = 1;
		public static final int axis_right_x = 2;
		public static final int axis_right_y = 3;*/
    /*public static final int a = 0;
    public static final int b = 1;
    public static final int x = 2;
    public static final int y = 3;
    public static final int select = 4;
    public static final int guide = 5;
    public static final int start = 6;
    public static final int l1 = 9;
    public static final int r1 = 10;
    public static final int dup = 11;
    public static final int ddown = 12;
    public static final int dleft = 13;
    public static final int dright = 14;

    // they are axis for sdl but buttons for the ps vita
    public static final int l2 = -4;
    public static final int r2 = -5;

    // button to switch between front and back touch
    public static final int touch = 1024;

    public static final int axis_left_x = 0;
    public static final int axis_left_y = 1;
    public static final int axis_right_x = 2;
    public static final int axis_right_y = 3;*/
  }
}
