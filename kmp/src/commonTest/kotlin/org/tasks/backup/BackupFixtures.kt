package org.tasks.backup

// Hand-written backups in the shape each app version exported, with field names taken from the
// entity classes at that time (git history). Not exported from real devices.

/** First JSON format (version 491, Feb 2018): Gson, Java field names, legacy locations and picture JSON. */
const val BACKUP_V491 = """
{
  "version": 491,
  "timestamp": 1518472496000,
  "data": {
    "tasks": [
      {
        "task": {
          "title": "Call mom",
          "priority": 1,
          "dueDate": 1518562800000,
          "hideUntil": 0,
          "created": 1518472400000,
          "modified": 1518472450000,
          "completed": 0,
          "deleted": 0,
          "notes": "",
          "estimatedSeconds": 0,
          "elapsedSeconds": 0,
          "timerStart": 0,
          "notificationFlags": 2,
          "notifications": 0,
          "lastNotified": 0,
          "snoozeTime": 0,
          "recurrence": "RRULE:FREQ=WEEKLY;INTERVAL=1;FROM=COMPLETION",
          "repeatUntil": 0,
          "calendarUri": "",
          "remoteId": "5b1f6f3e9b2d4a7c"
        },
        "alarms": [{"time": 1518559200000}],
        "locations": [{"name": "Home", "latitude": 40.7128, "longitude": -74.006, "radius": 250}],
        "tags": [{"name": "family", "tagUid": "0f3c9a51d2"}],
        "google": [
          {
            "remoteId": "MTIzNDU2Nzg5",
            "listId": "MDk4NzY1NDMyMQ",
            "parent": 0,
            "indent": 0,
            "order": 0,
            "remoteOrder": 3,
            "lastSync": 1518472460000,
            "deleted": 0
          }
        ],
        "comments": [
          {
            "remoteId": "a4c2e1",
            "message": "Photo of the list",
            "picture": "{\"uri\":\"content://org.tasks/attachments/list.jpg\"}",
            "created": 1518472470000
          }
        ]
      },
      {
        "task": {"title": "Pay rent", "priority": 3, "remoteId": "7d2a0c91e4"},
        "alarms": [],
        "locations": [],
        "tags": [],
        "google": [],
        "comments": []
      }
    ],
    "tags": [{"remoteId": "0f3c9a51d2", "name": "family", "color": 2, "tagOrdering": "[]"}],
    "filters": [
      {
        "title": "Due today",
        "sql": "WHERE tasks.dueDate<=(strftime('%s','now','localtime','start of day','+1 day')*1000)",
        "values": "",
        "criterion": "dueDate\t0\t\t0\nimportance\t1\t\t1"
      }
    ],
    "googleTaskLists": [
      {"remoteId": "MDk4NzY1NDMyMQ", "title": "My Tasks", "remoteOrder": 0, "lastSync": 1518472460000, "deleted": 0, "color": 7}
    ]
  }
}
"""

/** Version 111201 (Nov 2021): Gson, Kotlin field names, CalDAV, places, preferences, numeric icons. */
const val BACKUP_V111201 =
    """{"version":111201,"timestamp":1637150500000,"data":{"tasks":[""" +
        """{"task":{"title":"Water plants","priority":2,"dueDate":1637226000000,"hideUntil":0,"creationDate":1637100000000,""" +
        """"modificationDate":1637150000000,"completionDate":0,"deletionDate":0,"notes":"Ficus & cactus",""" +
        """"estimatedSeconds":0,"elapsedSeconds":0,"timerStart":0,"reminderFlags":6,"reminderPeriod":0,"reminderLast":0,""" +
        """"reminderSnooze":0,"recurrence":"RRULE:FREQ=DAILY;INTERVAL=3","repeatUntil":0,"calendarURI":"",""" +
        """"remoteId":"9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11","isCollapsed":false,"parent":0},""" +
        """"alarms":[{"time":1637222400000}],""" +
        """"geofences":[{"place":"b7e0c3a2-5c1d-4e3f-9a8b-7c6d5e4f3a2b","radius":100,"isArrival":true,"isDeparture":false}],""" +
        """"tags":[{"name":"home","tagUid":"3e51b0c4-7a2d-4f19-8c3e-1d2f3a4b5c6d","taskUid":"9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11"}],""" +
        """"google":[],"comments":[],"attachments":[],""" +
        """"caldavTasks":[{"calendar":"4f2b8d1e-6a3c-4b5d-9e8f-0a1b2c3d4e5f","object":"9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11.ics",""" +
        """"remoteId":"9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11","etag":"\"1637150000\"","lastSync":1637150001000,"deleted":0,""" +
        """"vtodo":"BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11\r\nSUMMARY:Water plants\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"}]},""" +
        """{"task":{"title":"Renew passport","priority":0,"dueDate":1640995200000,"creationDate":1637000000000,""" +
        """"modificationDate":1637200000000,"reminderFlags":2,"remoteId":"e2d1c0b9-a8f7-4e6d-5c4b-3a2918070605"},""" +
        """"alarms":[],"geofences":[],"tags":[],"comments":[],"attachments":[],"caldavTasks":[],""" +
        """"google":[{"remoteId":"MTE2NzQ1MDk4","listId":"MTE2MjM0NTY3","remoteParent":"","remoteOrder":1,""" +
        """"lastSync":1637100000000,"deleted":0,"isMoved":false}]}""" +
        """],"places":[{"uid":"b7e0c3a2-5c1d-4e3f-9a8b-7c6d5e4f3a2b","name":"Garden centre","address":"1 Main St","phone":"",""" +
        """"url":"","latitude":51.5,"longitude":-0.12,"color":0,"icon":-1,"order":-1}],""" +
        """"tags":[{"remoteId":"3e51b0c4-7a2d-4f19-8c3e-1d2f3a4b5c6d","name":"home","color":-14575885,"tagOrdering":"[]","icon":1062,"order":-1}],""" +
        """"filters":[{"title":"Overdue","sql":"WHERE tasks.dueDate<NOW()","values":"{}","criterion":"","color":0,"icon":-1,"order":-1}],""" +
        """"googleTaskAccounts":[{"account":"alice@gmail.com","error":"","etag":"\"abc\"","isCollapsed":false}],""" +
        """"googleTaskLists":[{"account":"alice@gmail.com","remoteId":"MTE2MjM0NTY3","title":"My Tasks","order":-1,""" +
        """"lastSync":1637150000000,"color":0,"icon":1041}],""" +
        """"caldavAccounts":[{"uuid":"a1f04c2e-9b8d-4e7f-a6b5-c4d3e2f1a0b9","name":"Nextcloud","url":"https://cloud.example.com/remote.php/dav/",""" +
        """"username":"alice","password":"","error":"","isSuppressRepeatingTasks":false,"accountType":0,"isCollapsed":false}],""" +
        """"caldavCalendars":[{"account":"a1f04c2e-9b8d-4e7f-a6b5-c4d3e2f1a0b9","uuid":"4f2b8d1e-6a3c-4b5d-9e8f-0a1b2c3d4e5f",""" +
        """"name":"Personal","color":-769226,"ctag":"http://sabre.io/ns/sync/42",""" +
        """"url":"https://cloud.example.com/remote.php/dav/calendars/alice/personal/","icon":-1,"order":-1,"access":0}],""" +
        """"taskListMetadata":[{"tagUuid":"3e51b0c4-7a2d-4f19-8c3e-1d2f3a4b5c6d","filter":"","taskIds":"[]"}],""" +
        """"intPrefs":{"p_theme":2,"p_default_importance_key":2},""" +
        """"longPrefs":{"p_last_sync":1637150000000},""" +
        """"stringPrefs":{"p_default_reminders_mode_key":"0","notif_default_reminder":"4"},""" +
        """"boolPrefs":{"p_show_completed_tasks":true},""" +
        """"setPrefs":{"p_default_reminders_key":["2","4"]}}}"""

/** Current format (version 151306): streamed by kotlinx.serialization, defaults omitted, dirty versions, vtodo. */
const val BACKUP_V151306 =
    """{"version":151306,"timestamp":1793000000000,"data":{"tasks":[""" +
        """{"task":{"title":"Café ✓ 🙂","dueDate":1793080000000,"creationDate":1792900000000,"modificationDate":1792990000000,""" +
        """"ringFlags":2,"remoteId":"0b6c2f4e-1a3d-4e5f-8a9b-0c1d2e3f4a5b"},"dirtyVersion":3,"syncedVersion":2,""" +
        """"alarms":[{"time":-900000,"type":2}],"geofences":[],"tags":[],"comments":[],"attachments":[],""" +
        """"vtodo":"BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:0b6c2f4e-1a3d-4e5f-8a9b-0c1d2e3f4a5b\r\nSUMMARY:Café\r\nEND:VTODO\r\nEND:VCALENDAR\r\n",""" +
        """"caldavTasks":[{"calendar":"c0ffee00-1111-2222-3333-444455556666","remoteId":"0b6c2f4e-1a3d-4e5f-8a9b-0c1d2e3f4a5b",""" +
        """"object":"0b6c2f4e-1a3d-4e5f-8a9b-0c1d2e3f4a5b.ics","etag":"\"e1\""}]},""" +
        """{"task":{"title":"Receipts","completionDate":1792995000000,"remoteId":"5f4e3d2c-1b0a-4f9e-8d7c-6b5a49382716"},""" +
        """"alarms":[],"geofences":[],"tags":[{"name":"work","tagUid":"7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d"}],""" +
        """"comments":[{"remoteId":"c1","message":"Scanned","created":1792995100000}],""" +
        """"attachments":[{"attachmentUid":"att-1"}],"caldavTasks":[]}""" +
        """],"places":[],""" +
        """"tags":[{"remoteId":"7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d","name":"work","color":-16738680,"icon":"work_outline"}],""" +
        """"filters":[],""" +
        """"caldavAccounts":[{"uuid":"tasks-account","name":"Tasks.org","url":"https://caldav.tasks.org/calendars/user1/",""" +
        """"username":"user1","accountType":4}],""" +
        """"caldavCalendars":[{"account":"tasks-account","uuid":"c0ffee00-1111-2222-3333-444455556666","name":"Inbox",""" +
        """"url":"https://caldav.tasks.org/calendars/user1/inbox/"}],""" +
        """"taskListMetadata":[],""" +
        """"taskAttachments":[{"remoteId":"att-1","name":"receipt.pdf","uri":"content://org.tasks.file/attachments/receipt.pdf"}],""" +
        """"intPrefs":{},"longPrefs":{},"stringPrefs":{"p_theme_color":"7"},"boolPrefs":{},"setPrefs":{}}}"""
