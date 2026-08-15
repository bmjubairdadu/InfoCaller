# Phase 1: Minimal InfoCaller Project

This phase focuses on setting up the core structure of the InfoCaller app, including branding, basic navigation, search functionality, and a local Room database with mock data.

## Proposed Changes

### Configuration & Dependencies

#### [MODIFY] [libs.versions.toml](file:///D:/InfoCaller/gradle/libs.versions.toml)
- Add Room, Navigation, and KSP versions and libraries.

#### [MODIFY] [build.gradle.kts](file:///D:/InfoCaller/app/build.gradle.kts)
- Apply KSP plugin.
- Add Room and Navigation dependencies.

### Domain Layer

#### [NEW] [Caller.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/domain/model/Caller.kt)
- Core data model for Caller information.

#### [NEW] [CallerRepository.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/domain/repository/CallerRepository.kt)
- Interface for caller data operations.

### Data Layer (Local Room Database)

#### [NEW] [CallerEntity.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/data/local/entity/CallerEntity.kt)
- Room entity for storing caller info.

#### [NEW] [CallerDao.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/data/local/dao/CallerDao.kt)
- Data Access Object for Room.

#### [NEW] [AppDatabase.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/data/local/database/AppDatabase.kt)
- Room database configuration.

#### [NEW] [CallerRepositoryImpl.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/data/repository/CallerRepositoryImpl.kt)
- Implementation of the repository interface using Room.

### UI Layer

#### [NEW] [NavGraph.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/navigation/NavGraph.kt)
- Navigation routes: Splash, Home, Search, Details.

#### [NEW] [SplashScreen.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/screens/SplashScreen.kt)
- Simple branding screen.

#### [NEW] [HomeScreen.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/screens/HomeScreen.kt)
- Entry point with search bar and quick actions.

#### [NEW] [SearchScreen.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/screens/SearchScreen.kt)
- Displays search results.

#### [NEW] [DetailsScreen.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/screens/DetailsScreen.kt)
- Displays full caller profile.

#### [NEW] [CallerViewModel.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/ui/viewmodel/CallerViewModel.kt)
- Manages search state and database interactions.

#### [MODIFY] [MainActivity.kt](file:///D:/InfoCaller/app/src/main/java/com/infocaller/app/MainActivity.kt)
- Set up NavHost and basic theming.

### Resources

#### [MODIFY] [strings.xml](file:///D:/InfoCaller/app/src/main/res/values/strings.xml)
- Define app name and other UI strings.

## Verification Plan

### Automated Tests
- Unit tests for `CallerRepositoryImpl` using an in-memory database.
- ViewModel tests for search logic.

### Manual Verification
1. Launch the app and see the Splash screen.
2. Search for a mock phone number (e.g., `+8801700000001`).
3. Verify that the correct caller information is displayed.
4. Navigate to the Details screen and check the reputation/spam score.
5. Check the APK size after building the release version.
