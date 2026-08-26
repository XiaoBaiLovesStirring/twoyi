// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use libc::*;
use libc::{c_char, c_int};
use ndk::event::{MotionAction, MotionEvent};
use std::collections::HashMap;
use std::mem;
use std::thread;
use std::{io::Write};
use uinput_sys::*;

use std::sync::mpsc::{ channel, Sender};
use std::sync::Mutex;
use once_cell::sync::Lazy;

use log::info;

const FF_MAX: u16 = 0x7f;

const TOUCH_PATH: &'static str = "/data/data/io.twoyi/rootfs/dev/input/touch";
const TOUCH_DEVICE_NAME: &'static str = "vtouch";
const TOUCH_DEVICE_UNIQUE_ID: &'static str = "<vtouch 0>";

const KEY_DEVICE_NAME: &'static str = "vkey";
const KEY_DEVICE_UNIQUE_ID: &'static str = "<keyboard 0>";
const KEY_PATH: &'static str = "/data/data/io.twoyi/rootfs/dev/input/key0";

#[repr(C)]
#[derive(Clone, Copy)]
struct device_info {
    name: [c_char; 80],
    driver_version: c_int,
    id: input_id,
    physical_location: [c_char; 80],
    unique_id: [c_char; 80],
    key_bitmask: [u8; (KEY_MAX as usize + 1) / 8],
    abs_bitmask: [u8; (ABS_MAX as usize + 1) / 8],
    rel_bitmask: [u8; (REL_MAX as usize + 1) / 8],
    sw_bitmask: [u8; (SW_MAX as usize + 1) / 8],
    led_bitmask: [u8; (LED_MAX as usize + 1) / 8],
    ff_bitmask: [u8; (FF_MAX as usize + 1) / 8],
    prop_bitmask: [u8; (INPUT_PROP_MAX as usize + 1) / 8],
    abs_max: [u32; ABS_CNT as usize],
    abs_min: [u32; ABS_CNT as usize],
}

unsafe fn any_as_u8_slice<T: Sized>(p: &T) -> &[u8] {
    ::std::slice::from_raw_parts((p as *const T) as *const u8, ::std::mem::size_of::<T>())
}

fn copy_to_cstr<const COUNT: usize>(data: &str, arr: &mut [u8; COUNT]) {
    let cstr = std::ffi::CString::new(data).expect("create cstring failed");
    let bytes = cstr.as_bytes_with_nul();
    let mut len = bytes.len();
    if len >= COUNT {
        len = COUNT;
    }
    arr[..len].copy_from_slice(bytes);
}

const MAX_POINTERS: usize = 10;

static INPUT_SENDER: Lazy<Mutex<Option<Sender<input_event>>>> = Lazy::new(|| { Mutex::new(None)});
static KEY_SENDER: Lazy<Mutex<Option<Sender<input_event>>>> = Lazy::new(|| { Mutex::new(None)});

/// Track touch state per pointer ID: (slot_index, x, y, pressure, active)
static TOUCH_STATE: Lazy<Mutex<HashMap<i32, (usize, i32, i32, i32, bool)>>> =
    Lazy::new(|| { Mutex::new(HashMap::new()) });

/// Next available slot index
static NEXT_SLOT: Lazy<Mutex<usize>> = Lazy::new(|| { Mutex::new(0) });

/// Number of active pointers
static ACTIVE_POINTERS: Lazy<Mutex<usize>> = Lazy::new(|| { Mutex::new(0) });

pub fn start_input_system(width: i32, height: i32) {
    thread::spawn(move || {
        touch_server(width, height);
    });
    thread::spawn(|| {
        key_server();
    });
}

pub fn input_event_write(
    tx: &std::sync::mpsc::Sender<input_event>,
    kind: i32,
    code: i32,
    val: i32,
) {
    let mut tp = libc::timespec { tv_sec:0, tv_nsec: 0 };
    let _ = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut tp) };
    let tv = timeval {
        tv_sec: tp.tv_sec,
        tv_usec: tp.tv_nsec / 1000
    };

    let ev = input_event {
        kind: kind as u16,
        code: code as u16,
        value: val,
        time: tv,
    };
    let _ = tx.send(ev);
}

/// Send SYN_REPORT to commit a batch of events
fn syn_report(tx: &std::sync::mpsc::Sender<input_event>) {
    input_event_write(tx, EV_SYN, SYN_REPORT, SYN_REPORT);
}

/// Write single-touch protocol events (ABS_X/ABS_Y + BTN_TOUCH)
/// This is REQUIRED for Android to recognize the device as a touchscreen,
/// not a mouse/pointer device.
fn write_single_touch(tx: &std::sync::mpsc::Sender<input_event>, x: i32, y: i32, pressed: bool) {
    input_event_write(tx, EV_ABS, ABS_X, x);
    input_event_write(tx, EV_ABS, ABS_Y, y);
    if pressed {
        input_event_write(tx, EV_KEY, BTN_TOUCH, 1);
        input_event_write(tx, EV_KEY, BTN_TOOL_FINGER, 1);
    } else {
        input_event_write(tx, EV_KEY, BTN_TOUCH, 0);
        input_event_write(tx, EV_KEY, BTN_TOOL_FINGER, 0);
    }
}

pub fn handle_touch(ev: MotionEvent) {
    let opt = INPUT_SENDER.lock().unwrap();
    if let Some(ref fd) = *opt {

        let action = ev.action();
        let pointer_index = ev.pointer_index();

        match action {
            MotionAction::Down | MotionAction::PointerDown => {
                // New finger pressed
                let pointer = ev.pointer_at_index(pointer_index);
                let pointer_id = pointer.pointer_id();
                let x = pointer.x() as i32;
                let y = pointer.y() as i32;
                let pressure = (pointer.pressure() * 80.0) as i32;

                let mut state = TOUCH_STATE.lock().unwrap();
                let mut slot = NEXT_SLOT.lock().unwrap();
                let mut active = ACTIVE_POINTERS.lock().unwrap();

                // Assign a slot to this pointer
                let slot_idx = *slot;
                *slot = (*slot + 1) % MAX_POINTERS;
                *active += 1;

                info!("DOWN pointer={} slot={} x={} y={}", pointer_id, slot_idx, x, y);

                // --- Multi-touch protocol (Type B) ---
                input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot_idx as i32);
                input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, pointer_id);
                input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, x);
                input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, y);
                input_event_write(fd, EV_ABS, ABS_MT_PRESSURE, pressure);

                // --- Single-touch protocol (for the first finger) ---
                // This is CRITICAL: Android identifies touchscreen devices
                // by ABS_X/ABS_Y + INPUT_PROP_DIRECT. Without ABS_X/ABS_Y,
                // the device is treated as a mouse/pointer.
                if *active == 1 {
                    write_single_touch(fd, x, y, true);
                }

                syn_report(fd);

                // Store state
                state.insert(pointer_id, (slot_idx, x, y, pressure, true));
            }

            MotionAction::Up | MotionAction::Outside => {
                // Last finger lifted
                let mut state = TOUCH_STATE.lock().unwrap();
                let mut active = ACTIVE_POINTERS.lock().unwrap();

                info!("UP: last finger lifted, clearing {} pointers", *active);

                // --- Multi-touch: release all slots ---
                for (pid, (slot_idx, _, _, _, _)) in state.iter() {
                    info!("  releasing pointer {} slot {}", pid, slot_idx);
                    input_event_write(fd, EV_ABS, ABS_MT_SLOT, *slot_idx as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                }

                // --- Single-touch: release ---
                write_single_touch(fd, 0, 0, false);

                syn_report(fd);

                state.clear();
                *active = 0;
            }

            MotionAction::PointerUp => {
                // One finger lifted but others remain
                let pointer = ev.pointer_at_index(pointer_index);
                let pointer_id = pointer.pointer_id();

                let mut state = TOUCH_STATE.lock().unwrap();
                let mut active = ACTIVE_POINTERS.lock().unwrap();

                info!("POINTER_UP: pointer={}", pointer_id);

                if let Some(&(slot_idx, _, _, _, _)) = state.get(&pointer_id) {
                    // Release this slot
                    input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot_idx as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                    syn_report(fd);

                    state.remove(&pointer_id);
                    if *active > 0 {
                        *active -= 1;
                    }

                    // After removing, update single-touch position to the remaining finger
                    if *active > 0 {
                        // Find the first remaining pointer
                        if let Some((&_, &(_, rem_x, rem_y, rem_p, _))) = state.iter().next() {
                            write_single_touch(fd, rem_x, rem_y, true);
                            syn_report(fd);
                        }
                    }
                }
            }

            MotionAction::Move => {
                let mut state = TOUCH_STATE.lock().unwrap();

                // Iterate through ALL pointers in the event
                let pointer_count = ev.pointer_count();

                for i in 0..pointer_count {
                    let p = ev.pointer_at_index(i);
                    let pid = p.pointer_id();
                    let x = p.x() as i32;
                    let y = p.y() as i32;
                    let pressure = (p.pressure() * 80.0) as i32;

                    // Update the state for this pointer
                    if let Some(entry) = state.get_mut(&pid) {
                        let (slot_idx, _, _, _, _) = *entry;

                        // Only write if coordinates actually changed
                        if entry.1 != x || entry.2 != y || entry.3 != pressure {
                            // --- Multi-touch ---
                            input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot_idx as i32);
                            input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, x);
                            input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, y);
                            input_event_write(fd, EV_ABS, ABS_MT_PRESSURE, pressure);

                            // --- Single-touch: update ABS_X/ABS_Y for slot 0 ---
                            // This ensures smooth tracking by keeping the single-touch
                            // protocol in sync with the first finger's position.
                            if slot_idx == 0 {
                                input_event_write(fd, EV_ABS, ABS_X, x);
                                input_event_write(fd, EV_ABS, ABS_Y, y);
                            }

                            entry.1 = x;
                            entry.2 = y;
                            entry.3 = pressure;
                        }
                    } else {
                        // New pointer in Move event - handle gracefully
                        info!("MOVE: new pointer {} detected, treating as DOWN", pid);
                        let mut slot = NEXT_SLOT.lock().unwrap();
                        let mut active = ACTIVE_POINTERS.lock().unwrap();

                        let slot_idx = *slot;
                        *slot = (*slot + 1) % MAX_POINTERS;
                        *active += 1;

                        input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot_idx as i32);
                        input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, pid);
                        input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, x);
                        input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, y);
                        input_event_write(fd, EV_ABS, ABS_MT_PRESSURE, pressure);

                        if *active == 1 {
                            write_single_touch(fd, x, y, true);
                        }

                        state.insert(pid, (slot_idx, x, y, pressure, true));
                    }
                }

                // Send a single SYN_REPORT for all moves
                if pointer_count > 0 {
                    syn_report(fd);
                }
            }

            MotionAction::Cancel => {
                // Cancel all touches
                let mut state = TOUCH_STATE.lock().unwrap();
                let mut active = ACTIVE_POINTERS.lock().unwrap();

                info!("CANCEL: clearing all touches");

                for (pid, (slot_idx, _, _, _, _)) in state.iter() {
                    input_event_write(fd, EV_ABS, ABS_MT_SLOT, *slot_idx as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                }

                write_single_touch(fd, 0, 0, false);
                syn_report(fd);

                state.clear();
                *active = 0;
            }

            _ => {}
        }
    }
}

fn generate_touch_device(width: i32, height: i32) -> device_info {
    let iid = input_id {
        product: 0x1,
        version: 0,
        vendor: 0,
        bustype: 0,
    };

    let mut info = device_info {
        name: unsafe { mem::zeroed() },
        driver_version: 0x1,
        id: iid,
        physical_location: unsafe { mem::zeroed() },
        unique_id: unsafe { mem::zeroed() },
        key_bitmask: unsafe { mem::zeroed() },
        abs_bitmask: unsafe { mem::zeroed() },
        rel_bitmask: unsafe { mem::zeroed() },
        sw_bitmask: unsafe { mem::zeroed() },
        led_bitmask: unsafe { mem::zeroed() },
        ff_bitmask: unsafe { mem::zeroed() },
        prop_bitmask: unsafe { mem::zeroed() },
        abs_max: [0; ABS_CNT as usize],
        abs_min: [0; ABS_CNT as usize],
    };

    copy_to_cstr(TOUCH_DEVICE_NAME, &mut info.name);
    copy_to_cstr(TOUCH_PATH, &mut info.physical_location);
    copy_to_cstr(TOUCH_DEVICE_UNIQUE_ID, &mut info.unique_id);

    // Mark as DIRECT touch input (NOT a pointer/mouse)
    // INPUT_PROP_DIRECT = 0x01 tells Android this is a touchscreen
    info.prop_bitmask[0] = INPUT_PROP_DIRECT as u8;

    // ===== Single-touch protocol (ABS_X/ABS_Y) =====
    // CRITICAL: Without ABS_X/ABS_Y, Android treats the device as a
    // mouse/pointer even with INPUT_PROP_DIRECT set.
    // ABS_X and ABS_Y are the single-touch absolute coordinates.
    let abs_x = ABS_X as usize;
    let abs_y = ABS_Y as usize;
    info.abs_bitmask[abs_x / 8] |= 1 << (abs_x % 8);
    info.abs_bitmask[abs_y / 8] |= 1 << (abs_y % 8);
    info.abs_min[ABS_X as usize] = 0;
    info.abs_max[ABS_X as usize] = width as u32;
    info.abs_min[ABS_Y as usize] = 0;
    info.abs_max[ABS_Y as usize] = height as u32;

    // ===== Multi-touch protocol (ABS_MT_*) =====
    // Enable EV_KEY codes for touch events
    info.key_bitmask[BTN_TOUCH as usize / 8] |= 1 << (BTN_TOUCH as usize % 8);
    info.key_bitmask[BTN_TOOL_FINGER as usize / 8] |= 1 << (BTN_TOOL_FINGER as usize % 8);

    // Enable ABS_MT capability bits
    let abs_mt_position_x = ABS_MT_POSITION_X as usize;
    let abs_mt_position_y = ABS_MT_POSITION_Y as usize;
    let abs_mt_pressure = ABS_MT_PRESSURE as usize;
    let abs_mt_slot = ABS_MT_SLOT as usize;
    let abs_mt_tracking_id = ABS_MT_TRACKING_ID as usize;
    let abs_mt_touch_major = ABS_MT_TOUCH_MAJOR as usize;
    info.abs_bitmask[abs_mt_position_x / 8] |= 1 << (abs_mt_position_x % 8);
    info.abs_bitmask[abs_mt_position_y / 8] |= 1 << (abs_mt_position_y % 8);
    info.abs_bitmask[abs_mt_pressure / 8] |= 1 << (abs_mt_pressure % 8);
    info.abs_bitmask[abs_mt_slot / 8] |= 1 << (abs_mt_slot % 8);
    info.abs_bitmask[abs_mt_tracking_id / 8] |= 1 << (abs_mt_tracking_id % 8);
    info.abs_bitmask[abs_mt_touch_major / 8] |= 1 << (abs_mt_touch_major % 8);

    // X axis (multi-touch)
    info.abs_min[ABS_MT_POSITION_X as usize] = 0;
    info.abs_max[ABS_MT_POSITION_X as usize] = width as u32;

    // Y axis (multi-touch)
    info.abs_min[ABS_MT_POSITION_Y as usize] = 0;
    info.abs_max[ABS_MT_POSITION_Y as usize] = height as u32;

    // Pressure
    info.abs_min[ABS_MT_PRESSURE as usize] = 0;
    info.abs_max[ABS_MT_PRESSURE as usize] = 255;

    // Slot (max 10 concurrent fingers)
    info.abs_min[ABS_MT_SLOT as usize] = 0;
    info.abs_max[ABS_MT_SLOT as usize] = (MAX_POINTERS - 1) as u32;

    // Tracking ID range
    info.abs_min[ABS_MT_TRACKING_ID as usize] = 0;
    info.abs_max[ABS_MT_TRACKING_ID as usize] = 65535;

    // Touch major/minor
    info.abs_min[ABS_MT_TOUCH_MAJOR as usize] = 0;
    info.abs_max[ABS_MT_TOUCH_MAJOR as usize] = 255;
    info.abs_min[ABS_MT_TOUCH_MINOR as usize] = 0;
    info.abs_max[ABS_MT_TOUCH_MINOR as usize] = 255;

    info
}

fn touch_server(width: i32, height: i32) {
    let device = generate_touch_device(width, height);
    let _ = std::fs::remove_file(TOUCH_PATH);
    let listener = unix_socket::UnixListener::bind(TOUCH_PATH).unwrap();
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("touch client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                let (tx, rx) = channel::<input_event>();
                *INPUT_SENDER.lock().unwrap() = Some(tx);

                thread::spawn(move || loop {
                    let ret = rx.recv();
                    if let Ok(ev) = ret {
                        let data = unsafe { any_as_u8_slice(&ev) };
                        let _ = stream.write_all(data);
                    }
                });
            }
            Err(_) => {
                info!("touch server error happened!");
                break;
            }
        }
    }

    info!("drop listener!");
}

fn generate_key_device() -> device_info {
    let mut info: device_info = unsafe { std::mem::MaybeUninit::zeroed().assume_init() };

    info.driver_version = 0x1;
    info.id.product = 0x1;

    copy_to_cstr(KEY_DEVICE_NAME, &mut info.name);
    copy_to_cstr(KEY_PATH, &mut info.physical_location);
    copy_to_cstr(KEY_DEVICE_UNIQUE_ID, &mut info.unique_id);

    info.key_bitmask[14] = 0x1C;

    info
}

pub fn send_key_code(_keycode: i32) {
    if let Some(ref tx) = *KEY_SENDER.lock().unwrap() {
        input_event_write(tx, EV_KEY, KEY_BACK, 1);
        input_event_write(tx, EV_SYN, SYN_REPORT, SYN_REPORT);
        input_event_write(tx, EV_KEY, KEY_BACK, 0);
    }
}

fn key_server() {
    let device = generate_key_device();
    let _ = std::fs::remove_file(KEY_PATH);
    let listener = unix_socket::UnixListener::bind(KEY_PATH).unwrap();
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("key client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                let (tx, rx) = channel::<input_event>();
                *KEY_SENDER.lock().unwrap() = Some(tx);

                thread::spawn(move || loop {
                    let ret = rx.recv();
                    if let Ok(ev) = ret {
                        let data = unsafe { any_as_u8_slice(&ev) };
                        let _ = stream.write_all(data);
                    }
                });
            }
            Err(_) => {
                info!("key server error happened!");
                break;
            }
        }
    }
}