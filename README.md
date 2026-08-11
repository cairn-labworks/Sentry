# OpenDash — Open-source Dash Cam for Android
Dash cameras are the most objective eyewitnesses on the road. The more drivers use dash cameras, the safer our trips and daily commutes are. OpenDash was born with the idea of making a dash camera accessible and available to anyone with a smartphone to make driving safer and more responsible. It is a project by the community for the community.

There is a number of dash camera applications out there, but all of them take up the entire screen that most drivers need for navigation, Uber or other applications that provide helpful on-screen information during the commute. The idea behind OpenDash is to create a minimally-intrusive on-screen widget, that will leave the major part of the screen to any other application the driver chooses to use.

## Design and Implementation
OpenDash is a dash camera widget, that is drawn over other apps, allowing users to record videos in the background while using other apps, such as navigation.

The core functionality of the application is performed by two services: [WidgetService](mobile/src/main/java/app/opendash/WidgetService.java), which draws the widget icons over other apps, and [BackgroundVideoRecorder](mobile/src/main/java/app/opendash/BackgroundVideoRecorder.java), which records videos in the background and rotates them.

The way the application works is the following:
1. User starts the app
1. Permissions are checked
1. Some navigation app is started in the background (see function here)
1. WidgetService and BackgroundVideoRecorder services are started.

### About BackgroundVideoRecorder service
This service continuously records video in pieces of specified length in the background, using the rear (primary) camera, and saves them in a dedicated application directory. Once the directory size reaches the specified quota, the videos get rotated (the oldest one gets deleted to create space for a new one).

When a new recording starts, we save the current and previous recording filenames in the database. We need this so that when users select "Save recording" we will mark the videos as "starred", so that they are not deleted during rotation.

When the video reaches the specified length, we let the MediaStore Content Provider know about the new file, and repeat the recording process.

### ViewRecordingsActivity
This is a browser for videos recorded by the app, built as a Recycler View. We get an arraylist of recordings by querying MediaStore, populating the list with Recording objects.

## Credits
OpenDash is a modernized rebrand of the original [Open Dash Cam](https://github.com/maxneaga/open_dash_cam_android) project by **Maxim Neaga** and its community contributors. See [LICENSE](LICENSE) for terms — commercial use requires written permission by Maxim Neaga.
