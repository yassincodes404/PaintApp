# Paint Studio

Scaffold for a JavaFX desktop drawing editor. Requires JDK 25 and Maven.

Set JAVA_HOME to JDK 25, then run the following command from the project root to open a blank Paint Studio window.

    mvn javafx:run

The packages under src/main/java/com/paintstudio cover application startup, documents, drawing, editor interactions, UI, and shared diagnostics. Stylesheets and icons belong under src/main/resources. Test packages mirror the drawing, document, and editor domains.

PaintApplication is the entry point. The other Java files and stylesheets are empty placeholders.
